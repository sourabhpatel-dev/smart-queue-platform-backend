package com.sourabh.smartqueue.queue.realtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import com.sourabh.smartqueue.infrastructure.redis.RedisJson;
import com.sourabh.smartqueue.queue.InvalidQueueRequestException;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueEntryStatus;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.QueueStatus;
import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;

/**
 * Time-driven freshness for live queue snapshots (see ADR-020).
 *
 * <p>Business events move queue state, but the project Clock moves rank and
 * waits without any database mutation (aging). The event-driven bridge alone
 * therefore leaves projections stale between events; this scheduler bounds
 * that drift to {@code queue.realtime.refresh.interval} per active queue.
 *
 * <p>Orchestration only, never business logic: discover queues holding live
 * entries, rebuild each snapshot from PostgreSQL through the shared
 * projection (shared rank query + shared estimator — nothing recomputed
 * here), renew the Redis value, and publish to the queue topic when the
 * live content materially changed. It never joins, calls, serves,
 * completes, cancels, or reprioritizes anything — read → calculate →
 * project, on committed PostgreSQL state (point-in-time semantics: a
 * refresh racing a business transaction captures either side, never a mix
 * beyond what PostgreSQL isolation already permits).
 *
 * <p>Best-effort infrastructure: Redis, broker, or PostgreSQL failures are
 * logged and skipped per queue (or per tick), never propagated, never
 * retried beyond one guarded-write retry — the next tick rebuilds again.
 */
@Service
@ConditionalOnProperty(prefix = "queue.realtime.refresh", name = "enabled",
		havingValue = "true", matchIfMissing = true)
public class QueueRealtimeRefreshService {

	private static final Logger log =
			LoggerFactory.getLogger(QueueRealtimeRefreshService.class);

	/** Per-queue outcome of one refresh attempt. */
	public enum Outcome {
		/** Redis renewed and the new live content published to the topic. */
		REFRESHED_BROADCAST,
		/** Redis renewed (TTL included) but the live content was unchanged. */
		REFRESHED_QUIET,
		/** Nothing written (unknown queue, Redis/PostgreSQL failure). */
		SKIPPED
	}

	/** One tick's accounting, by queue id. */
	public record RefreshSummary(
			List<UUID> considered,
			List<UUID> refreshed,
			List<UUID> broadcast,
			List<UUID> skipped) {
	}

	private final QueueRepository queues;
	private final QueueRealtimeProjectionService projections;
	private final SimpMessagingTemplate messaging;
	private final RedisJson json;

	public QueueRealtimeRefreshService(QueueRepository queues,
			QueueRealtimeProjectionService projections,
			SimpMessagingTemplate messaging,
			RedisJson json) {
		this.queues = queues;
		this.projections = projections;
		this.messaging = messaging;
		this.json = json;
	}

	/**
	 * Periodic tick. Fixed delay (not rate) so a slow pass never overlaps
	 * itself on this instance. Two instances may duplicate the work —
	 * accepted and documented (ADR-020): both rebuild from committed
	 * PostgreSQL state and the guarded write keeps them from regressing
	 * each other; no distributed lock is introduced for a best-effort
	 * derived-state refresh.
	 */
	@Scheduled(fixedDelayString = "${queue.realtime.refresh.interval:PT1M}")
	public void scheduledRefresh() {
		try {
			RefreshSummary summary = refreshAll();
			log.debug("queue realtime refresh: considered={} refreshed={} broadcast={} skipped={}",
					summary.considered().size(), summary.refreshed().size(),
					summary.broadcast().size(), summary.skipped().size());
		} catch (RuntimeException e) {
			log.warn("scheduled queue realtime refresh failed", e);
		}
	}

	/**
	 * Refreshes every queue currently holding live entries. One failure
	 * never stops the remaining queues.
	 */
	public RefreshSummary refreshAll() {
		List<UUID> ids = queues.findIdsRequiringRealtimeRefresh(QueueStatus.ACTIVE,
				List.of(QueueEntryStatus.WAITING, QueueEntryStatus.CALLED,
						QueueEntryStatus.SERVING));
		List<UUID> refreshed = new ArrayList<>();
		List<UUID> broadcast = new ArrayList<>();
		List<UUID> skipped = new ArrayList<>();
		for (UUID queueId : ids) {
			try {
				Outcome outcome = refreshQueue(queueId);
				if (outcome == Outcome.SKIPPED) {
					skipped.add(queueId);
				} else {
					refreshed.add(queueId);
					if (outcome == Outcome.REFRESHED_BROADCAST) {
						broadcast.add(queueId);
					}
				}
			} catch (RuntimeException e) {
				log.warn("queue realtime refresh failed for queue {}", queueId, e);
				skipped.add(queueId);
			}
		}
		return new RefreshSummary(List.copyOf(ids), refreshed, broadcast, skipped);
	}

	/**
	 * Refreshes one queue with stale-write protection: the Redis value is
	 * read before the PostgreSQL rebuild, and the write is a guarded
	 * compare-and-set against that observation. A concurrent event-driven
	 * refresh in between fails the guard; the stale snapshot is then
	 * discarded and rebuilt once from current state instead of overwriting
	 * the newer projection.
	 *
	 * <p>The scheduler discovers queues globally; for each one it resolves
	 * the queue's organization and branch from PostgreSQL and sets an
	 * explicit tenant context before calling the projection service. The
	 * context is always cleared in a finally block — background tasks never
	 * leak tenant scope to the next queue or thread.
	 */
	public Outcome refreshQueue(UUID queueId) {
		Queue queue = queues.findById(queueId)
				.orElseThrow(() -> new InvalidQueueRequestException("unknown queue: " + queueId));
		TenantContext previous = TenantContextHolder.getOrNull();
		TenantContextHolder.set(new TenantContext(
				queue.getOrganization().getId(), queue.getBranch().getId()));
		try {
			return refreshQueueInScope(queueId);
		} finally {
			TenantContextHolder.restore(previous);
		}
	}

	private Outcome refreshQueueInScope(UUID queueId) {
		String previousRaw;
		try {
			previousRaw = projections.readRawSnapshot(queueId);
		} catch (InvalidQueueRequestException e) {
			log.debug("queue realtime refresh skipped for unknown queue {}", queueId);
			return Outcome.SKIPPED;
		} catch (RuntimeException e) {
			log.warn("queue realtime refresh skipped for queue {} (projection read failed)",
					queueId, e);
			return Outcome.SKIPPED;
		}

		QueueRealtimeState fresh;
		try {
			fresh = projections.buildSnapshot(queueId);
		} catch (InvalidQueueRequestException e) {
			log.debug("queue realtime refresh skipped for unknown queue {}", queueId);
			return Outcome.SKIPPED;
		} catch (RuntimeException e) {
			log.warn("queue realtime refresh skipped for queue {} (rebuild failed)",
					queueId, e);
			return Outcome.SKIPPED;
		}

		Optional<QueueRealtimeState> previous = projections.parseStored(previousRaw);
		boolean changed = previous
				.map(stored -> !QueueRealtimeState.sameLiveContent(stored, fresh))
				.orElse(true);

		boolean stored;
		try {
			stored = projections.compareAndStore(fresh, previousRaw);
		} catch (RuntimeException e) {
			log.warn("queue realtime refresh skipped for queue {} (Redis write failed)",
					queueId, e);
			return Outcome.SKIPPED;
		}
		if (!stored) {
			return refreshAfterRace(queueId);
		}
		if (!changed) {
			return Outcome.REFRESHED_QUIET;
		}
		return publish(fresh) ? Outcome.REFRESHED_BROADCAST : Outcome.REFRESHED_QUIET;
	}

	/**
	 * Single retry after losing the guarded-write race: the discarded
	 * snapshot was built from pre-race PostgreSQL state, so rebuild once
	 * from current state and store that. An unconditional store is safe
	 * here — the content is the freshest read, and any further racer
	 * resolves last-write-wins between two fresh rebuilds.
	 */
	private Outcome refreshAfterRace(UUID queueId) {
		try {
			String currentRaw = projections.readRawSnapshot(queueId);
			Optional<QueueRealtimeState> current = projections.parseStored(currentRaw);
			QueueRealtimeState rebuilt = projections.buildSnapshot(queueId);
			boolean changed = current
					.map(stored -> !QueueRealtimeState.sameLiveContent(stored, rebuilt))
					.orElse(true);
			projections.store(rebuilt);
			if (!changed) {
				return Outcome.REFRESHED_QUIET;
			}
			return publish(rebuilt) ? Outcome.REFRESHED_BROADCAST : Outcome.REFRESHED_QUIET;
		} catch (RuntimeException e) {
			log.warn("queue realtime refresh retry skipped for queue {}", queueId, e);
			return Outcome.SKIPPED;
		}
	}

	/**
	 * Best-effort broadcast on the same topic contract as the event-driven
	 * bridge. A broker failure degrades live delivery only — Redis already
	 * holds the fresh snapshot and PostgreSQL was never involved.
	 */
	private boolean publish(QueueRealtimeState snapshot) {
		try {
			messaging.convertAndSend(
					QueueRealtimeDestinations.topic(snapshot.organizationId(),
							snapshot.branchId(), snapshot.queueId()),
					json.toJson(snapshot),
					Map.of(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON));
			return true;
		} catch (RuntimeException e) {
			log.warn("queue realtime refresh broadcast failed for queue {}",
					snapshot.queueId(), e);
			return false;
		}
	}
}
