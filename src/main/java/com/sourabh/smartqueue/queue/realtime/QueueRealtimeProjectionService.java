package com.sourabh.smartqueue.queue.realtime;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.infrastructure.redis.RedisJson;
import com.sourabh.smartqueue.infrastructure.redis.RedisKeyConvention;
import com.sourabh.smartqueue.queue.InvalidQueueRequestException;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueAgingProperties;
import com.sourabh.smartqueue.queue.QueueEntryNotFoundException;
import com.sourabh.smartqueue.queue.QueueEntry;
import com.sourabh.smartqueue.queue.QueueEntryRepository;
import com.sourabh.smartqueue.queue.QueueEntryStatus;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.QueueWaitTimeEstimator;
import com.sourabh.smartqueue.queue.QueueWaitTimeQueries;
import com.sourabh.smartqueue.queue.WaitTimeEstimate;
import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.tenant.TenantContextRequiredException;

/**
 * Builds the public real-time snapshot of one queue (see ADR-018).
 *
 * <p>PostgreSQL → snapshot → Redis, in that order, every time: the
 * projection never reads its own previous output, so re-running it is
 * always safe (idempotent rebuilds, crash recovery, cold starts). This
 * same method is the rebuild path — there is no separate reconciliation
 * flow. Entries come pre-ranked from the shared claim ordering; waits
 * come from the shared estimator; nothing here reimplements either.
 *
 * <p>Snapshots carry an explicit TTL (freshness bound, not correctness —
 * the database re-derives on demand). Event-driven snapshots advance on
 * business events; the scheduled freshness pass (ADR-020) rebuilds active
 * queues periodically so time-driven drift (aging, waits) stays bounded.
 */
@Service
public class QueueRealtimeProjectionService {

	static final Duration SNAPSHOT_TTL = Duration.ofMinutes(5);

	/**
	 * Atomic compare-and-set for the scheduled refresh path (see ADR-020):
	 * writes {@code snapshot} only if the stored value still equals
	 * {@code expectedRaw} (the value observed before the PostgreSQL rebuild,
	 * or {@code null} when no value was stored). A concurrent event-driven
	 * refresh between the read and the write fails the comparison instead of
	 * being silently reverted to older state.
	 */
	private static final DefaultRedisScript<Long> COMPARE_AND_STORE =
			new DefaultRedisScript<>(
					"""
					local current = redis.call('GET', KEYS[1])
					if (current == false and ARGV[1] == '__MISSING__')
					  or (current ~= false and current == ARGV[1]) then
					  redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
					  return 1
					else
					  return 0
					end
					""",
					Long.class);

	private final QueueRepository queues;
	private final QueueEntryRepository entries;
	private final QueueWaitTimeQueries queries;
	private final QueueWaitTimeEstimator estimator;
	private final StringRedisTemplate redis;
	private final RedisJson json;
	private final Clock clock;
	private final QueueAgingProperties aging;

	public QueueRealtimeProjectionService(QueueRepository queues,
			QueueEntryRepository entries,
			QueueWaitTimeQueries queries,
			QueueWaitTimeEstimator estimator,
			StringRedisTemplate redis,
			RedisJson json,
			Clock clock,
			QueueAgingProperties aging) {
		this.queues = queues;
		this.entries = entries;
		this.queries = queries;
		this.estimator = estimator;
		this.redis = redis;
		this.json = json;
		this.clock = clock;
		this.aging = aging;
	}

	/**
	 * Rebuilds the snapshot from current PostgreSQL state and replaces the
	 * Redis value. Also the recovery path: missing or stale snapshots are
	 * recreated by calling this, never by patching Redis directly.
	 * Unconditional by design — this is the event-driven path, whose freshly
	 * committed state is the newest known at build time.
	 */
	@Transactional
	public QueueRealtimeState refresh(UUID queueId) {
		QueueRealtimeState snapshot = buildSnapshot(queueId);
		store(snapshot);
		return snapshot;
	}

	/**
	 * Rebuilds the snapshot from current PostgreSQL state without touching
	 * Redis. Read-only: the scheduled freshness pass (ADR-020) builds first
	 * and decides about the Redis write afterwards, so an unchanged rebuild
	 * can renew the TTL quietly and a raced rebuild can be discarded.
	 *
	 * @throws InvalidQueueRequestException for an unknown queue or a queue
	 * outside the current tenant scope.
	 * @throws TenantContextRequiredException if no tenant context is set.
	 */
	@Transactional(readOnly = true)
	public QueueRealtimeState buildSnapshot(UUID queueId) {
		var context = TenantContextHolder.get();
		Queue queue = queues.findByIdAndOrganizationId(queueId, context.organizationId())
				.orElseThrow(() -> new InvalidQueueRequestException("unknown queue: " + queueId));
		if (context.isBranchScoped() && !context.branchId().equals(queue.getBranch().getId())) {
			throw new InvalidQueueRequestException("unknown queue: " + queueId);
		}
		long nowEpoch = clock.instant().getEpochSecond();
		List<QueueEntry> waiting = queries.waitingInRankOrder(queueId, nowEpoch,
				aging.getIntervalMinutes(), aging.getMaxPromotions(), aging.isEnabled());

		List<QueueEntryView> views = waiting.stream()
				.map(entry -> new QueueEntryView(entry.getTokenNumber(), entry.getPriority(),
						entry.getStatus(), waitOrNull(entry.getId())))
				.toList();
		List<QueueEntry> active = entries.findByQueueAndStatusIn(queue,
				List.of(QueueEntryStatus.CALLED, QueueEntryStatus.SERVING));
		List<Integer> calledTokens = tokensInOrder(active, QueueEntryStatus.CALLED);
		List<Integer> servingTokens = tokensInOrder(active, QueueEntryStatus.SERVING);

		return new QueueRealtimeState(
				queue.getOrganization().getId(), queue.getBranch().getId(), queue.getId(),
				queue.getService().getId(), queue.getStatus(), clock.instant(),
				views.size(), calledTokens.size(), servingTokens.size(),
				views, calledTokens, servingTokens);
	}

	/**
	 * Unconditionally replaces the Redis snapshot (event-driven and retry
	 * paths). Renews the TTL on every write, changed or not.
	 */
	public void store(QueueRealtimeState snapshot) {
		redis.opsForValue().set(
				RedisKeyConvention.queueState(snapshot.organizationId(), snapshot.branchId(),
						snapshot.queueId()),
				json.toJson(snapshot), SNAPSHOT_TTL);
	}

	/**
	 * The raw stored snapshot JSON, or {@code null} when no snapshot is
	 * stored. The scheduled refresh reads this <em>before</em> rebuilding
	 * from PostgreSQL so the guarded write can detect a concurrent
	 * event-driven refresh. Redis failures propagate — callers treat them
	 * as a skipped refresh, never as business-state failures.
	 *
	 * @throws InvalidQueueRequestException for an unknown queue or a queue
	 * outside the current tenant scope.
	 * @throws TenantContextRequiredException if no tenant context is set.
	 */
	public String readRawSnapshot(UUID queueId) {
		var context = TenantContextHolder.get();
		Queue queue = queues.findByIdAndOrganizationId(queueId, context.organizationId())
				.orElseThrow(() -> new InvalidQueueRequestException("unknown queue: " + queueId));
		if (context.isBranchScoped() && !context.branchId().equals(queue.getBranch().getId())) {
			throw new InvalidQueueRequestException("unknown queue: " + queueId);
		}
		return redis.opsForValue().get(
				RedisKeyConvention.queueState(queue.getOrganization().getId(),
						queue.getBranch().getId(), queue.getId()));
	}

	/**
	 * Parses stored snapshot JSON leniently: missing or unparseable content
	 * (evicted key, foreign writer — neither expected, both survivable)
	 * yields empty instead of failing the refresh.
	 */
	public Optional<QueueRealtimeState> parseStored(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(json.fromJson(raw, QueueRealtimeState.class));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/**
	 * Atomically stores {@code snapshot} only if the current Redis value
	 * still equals {@code expectedRaw} (or is still absent when
	 * {@code expectedRaw} is {@code null}).
	 *
	 * @return {@code true} when the write happened (TTL renewed); {@code false}
	 * when a concurrent refresh won the race, in which case the caller must
	 * not treat its snapshot as current.
	 */
	public boolean compareAndStore(QueueRealtimeState snapshot, String expectedRaw) {
		String key = RedisKeyConvention.queueState(snapshot.organizationId(),
				snapshot.branchId(), snapshot.queueId());
		Long stored = redis.execute(COMPARE_AND_STORE, List.of(key),
				expectedRaw != null ? expectedRaw : "__MISSING__",
				json.toJson(snapshot), String.valueOf(SNAPSHOT_TTL.getSeconds()));
		return Long.valueOf(1L).equals(stored);
	}

	private Integer waitOrNull(UUID entryId) {
		return estimator.estimate(entryId).map(WaitTimeEstimate::waitMinutes).orElse(null);
	}

	/**
	 * Resolves the queue owning an entry (for session events, which carry
	 * the entry but no queue id).
	 */
	@Transactional(readOnly = true)
	public UUID queueOf(UUID queueEntryId) {
		return entries.findById(queueEntryId)
				.orElseThrow(() -> new QueueEntryNotFoundException(
						"unknown queue entry: " + queueEntryId))
				.getQueue().getId();
	}

	private static List<Integer> tokensInOrder(List<QueueEntry> active, QueueEntryStatus status) {
		return active.stream()
				.filter(entry -> entry.getStatus() == status)
				.sorted(Comparator.comparing(QueueEntry::getJoinedAt)
						.thenComparing(QueueEntry::getId))
				.map(QueueEntry::getTokenNumber)
				.toList();
	}
}
