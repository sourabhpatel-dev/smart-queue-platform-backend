package com.sourabh.smartqueue.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sourabh.smartqueue.notification.NotificationRepository;
import com.sourabh.smartqueue.notification.NotificationRetentionProperties;

/**
 * Scheduled notification retention sweep (see ADR-033).
 *
 * <p>Orchestration only, never business logic: each tick computes the
 * read/unread cutoffs from the project {@link Clock} and deletes
 * eligible rows in bounded id batches. Cleanup is fully independent
 * from notification creation — no shared transaction, no shared code
 * path, no events emitted, no new notifications created.
 *
 * <p>Each batch runs in its own transaction (via {@link TransactionTemplate},
 * not a tick-wide transaction), so a large backlog deletes
 * incrementally: every committed batch stays deleted even if a later
 * batch fails, and the next tick retries whatever remains. A row read
 * (marked read) between the id page and its delete simply moves to the
 * other predicate's next pass — deletion stays idempotent either way.
 *
 * <p>Best-effort maintenance: any failure is logged with cutoffs and
 * counts (never notification contents) and swallowed — the scheduler
 * must never die, and the next tick retries naturally. Two instances
 * may sweep concurrently; both delete disjoint-or-identical id sets
 * from committed PostgreSQL state and the bulk delete is naturally
 * idempotent, so no distributed lock is introduced for janitorial work.
 */
@Service
@ConditionalOnProperty(prefix = "notifications.retention", name = "enabled",
		havingValue = "true", matchIfMissing = true)
public class NotificationRetentionService {

	private static final Logger log =
			LoggerFactory.getLogger(NotificationRetentionService.class);

	/**
	 * Rows deleted per statement. Bounds statement size and memory (ids
	 * only, never entities) without a batching framework: the loop
	 * repeats while a full batch comes back. Public so tests and
	 * operators can reason about the batch boundary.
	 */
	public static final int DELETE_BATCH_SIZE = 500;

	private final NotificationRetentionProperties policy;
	private final NotificationRepository notifications;
	private final Clock clock;
	private final TransactionTemplate transactions;

	public NotificationRetentionService(NotificationRetentionProperties policy,
			NotificationRepository notifications,
			Clock clock,
			PlatformTransactionManager transactionManager) {
		this.policy = policy;
		this.notifications = notifications;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/**
	 * Periodic tick. Fixed delay (not rate) so a slow pass never overlaps
	 * itself on this instance. Never throws outward — a failed tick logs
	 * and the next one retries.
	 */
	@Scheduled(fixedDelayString = "${notifications.retention.interval:PT6H}")
	public void scheduledCleanup() {
		try {
			PurgeSummary summary = purgeOnce();
			log.info("notification retention cleanup: readCutoff={} unreadCutoff={} deletedRead={} deletedUnread={}",
					summary.readCutoff(), summary.unreadCutoff(),
					summary.deletedRead(), summary.deletedUnread());
		} catch (RuntimeException e) {
			log.warn("scheduled notification retention cleanup failed; next tick retries", e);
		}
	}

	/** One sweep's accounting. Cutoffs are instants, counts are rows deleted. */
	public record PurgeSummary(
			Instant readCutoff,
			Instant unreadCutoff,
			long deletedRead,
			long deletedUnread) {
	}

	/**
	 * Deletes every eligible row: read rows older than
	 * {@code readAfter}, unread rows older than {@code unreadAfter},
	 * both anchored on {@code createdAt}. Eligibility is strict
	 * {@code createdAt < cutoff} — a row exactly at the cutoff is kept,
	 * making boundary behavior deterministic.
	 */
	public PurgeSummary purgeOnce() {
		Instant now = clock.instant();
		Instant readCutoff = now.minus(policy.getReadAfter());
		Instant unreadCutoff = now.minus(policy.getUnreadAfter());
		long deletedRead = purgePredicate(readCutoff, true);
		long deletedUnread = purgePredicate(unreadCutoff, false);
		return new PurgeSummary(readCutoff, unreadCutoff, deletedRead, deletedUnread);
	}

	private long purgePredicate(Instant cutoff, boolean read) {
		long deleted = 0;
		while (true) {
			List<UUID> batch = transactions.execute(status ->
					read
							? notifications.findReadIdsEligibleForRetention(cutoff,
									PageRequest.of(0, DELETE_BATCH_SIZE))
							: notifications.findUnreadIdsEligibleForRetention(cutoff,
									PageRequest.of(0, DELETE_BATCH_SIZE)));
			if (batch == null || batch.isEmpty()) {
				return deleted;
			}
			Integer removed = transactions.execute(status -> notifications.deleteByIds(batch));
			deleted += removed == null ? 0 : removed;
			if (batch.size() < DELETE_BATCH_SIZE) {
				return deleted;
			}
		}
	}
}
