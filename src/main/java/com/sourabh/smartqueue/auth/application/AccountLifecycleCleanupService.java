package com.sourabh.smartqueue.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sourabh.smartqueue.auth.AccountVerificationTokenRepository;
import com.sourabh.smartqueue.auth.OrganizationInvitationRepository;
import com.sourabh.smartqueue.auth.PasswordResetTokenRepository;

/**
 * Scheduled cleanup for consumed-or-expired lifecycle rows (see
 * ADR-037).
 *
 * <p>Orchestration only: each tick deletes eligible token and
 * invitation rows in bounded id batches (ids only, never entities),
 * each batch in its own transaction. Live rows are never eligible
 * (their predicates require used/accepted/revoked state or past
 * expiry). Cleanup is independent from creation and acceptance —
 * no shared transaction, no shared code path, no events. Failures
 * log with counts (never tokens or emails) and retry next tick;
 * concurrent instances delete disjoint-or-identical id sets from
 * committed state, so no lock is introduced for janitorial work
 * (the Phase 4E retention shape).
 */
@Service
@ConditionalOnProperty(prefix = "app.account.cleanup", name = "enabled",
		havingValue = "true", matchIfMissing = true)
public class AccountLifecycleCleanupService {

	private static final Logger log =
			LoggerFactory.getLogger(AccountLifecycleCleanupService.class);

	/**
	 * Rows deleted per statement. Bounds statement size and memory
	 * without a batching framework: each predicate loops while a
	 * full batch returns.
	 */
	public static final int DELETE_BATCH_SIZE = 500;

	private final AccountVerificationTokenRepository verifications;
	private final PasswordResetTokenRepository resets;
	private final OrganizationInvitationRepository invitations;
	private final Clock clock;
	private final TransactionTemplate transactions;

	public AccountLifecycleCleanupService(
			AccountVerificationTokenRepository verifications,
			PasswordResetTokenRepository resets,
			OrganizationInvitationRepository invitations,
			Clock clock,
			PlatformTransactionManager transactionManager) {
		this.verifications = verifications;
		this.resets = resets;
		this.invitations = invitations;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/**
	 * Periodic tick. Fixed delay (not rate) so a slow pass never
	 * overlaps itself on this instance. Never throws outward.
	 */
	@Scheduled(fixedDelayString = "${app.account.cleanup.interval:PT24H}")
	public void scheduledCleanup() {
		try {
			CleanupSummary summary = purgeOnce();
			log.info("account lifecycle cleanup: verifications={} resets={} invitations={}",
					summary.verifications(), summary.resets(), summary.invitations());
		} catch (RuntimeException e) {
			log.warn("scheduled account lifecycle cleanup failed; next tick retries", e);
		}
	}

	/** One sweep's accounting (row counts only). */
	public record CleanupSummary(long verifications, long resets, long invitations) {
	}

	public CleanupSummary purgeOnce() {
		Instant now = clock.instant();
		long verifications = purgeBatches(
				() -> this.verifications.findIdsEligibleForCleanup(
						now, PageRequest.of(0, DELETE_BATCH_SIZE)),
				this.verifications::deleteByIds);
		long resets = purgeBatches(
				() -> this.resets.findIdsEligibleForCleanup(
						now, PageRequest.of(0, DELETE_BATCH_SIZE)),
				this.resets::deleteByIds);
		long invitations = purgeBatches(
				() -> this.invitations.findIdsEligibleForCleanup(
						now, PageRequest.of(0, DELETE_BATCH_SIZE)),
				this.invitations::deleteByIds);
		return new CleanupSummary(verifications, resets, invitations);
	}

	private long purgeBatches(Supplier<List<UUID>> nextIds,
			java.util.function.Function<java.util.Collection<UUID>, Integer> remover) {
		long deleted = 0;
		while (true) {
			List<UUID> batch = transactions.execute(status -> nextIds.get());
			if (batch == null || batch.isEmpty()) {
				return deleted;
			}
			Integer removed = transactions.execute(status -> remover.apply(batch));
			deleted += removed == null ? 0 : removed;
			if (batch.size() < DELETE_BATCH_SIZE) {
				return deleted;
			}
		}
	}
}
