package com.sourabh.smartqueue.queue;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/**
 * Persistence access for {@link QueueEntry}.
 *
 * <p>Intentionally query-free except the atomic claim (in
 * {@link QueueEntryClaimRepository}) and the locked load below: no other
 * caller exists yet (Phase 2G has no APIs).
 */
public interface QueueEntryRepository extends JpaRepository<QueueEntry, UUID> {

	/**
	 * Row-locked load for start/complete transitions: concurrent callers
	 * serialize on the row, so the second observes the first's committed
	 * state instead of racing it.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT e FROM QueueEntry e WHERE e.id = :entryId")
	Optional<QueueEntry> findLockedById(@Param("entryId") UUID entryId);

	/**
	 * Active entries of one queue (unordered) — the snapshot builder orders
	 * WAITING rows through the shared rank query; the rest groups by status.
	 */
	List<QueueEntry> findByQueueAndStatusIn(Queue queue, Collection<QueueEntryStatus> statuses);

	/**
	 * Scope-aware load: returns the entry only when it belongs to the given
	 * organization. Used by tenant-scoped application services (ADR-019).
	 */
	Optional<QueueEntry> findByIdAndOrganizationId(UUID id, UUID organizationId);

	/**
	 * Conjunctive record search for one queue (see ADR-027): every facet
	 * is nullable, all parameters are UUID/enum-typed so the
	 * {@code IS NULL} checks plan cleanly. Rank ordering is deliberately
	 * NOT a sort option here — live rank lives in the snapshot/single
	 * reads via the shared rank query; this listing sorts by stored
	 * fields only.
	 */
	@Query(value = """
			SELECT e FROM QueueEntry e
			WHERE e.queue.id = :queueId
			AND e.organization.id = :organizationId
			AND (:customerId IS NULL OR e.customer.id = :customerId)
			AND (:status IS NULL OR e.status = :status)
			AND (:priority IS NULL OR e.priority = :priority)
			""",
			countQuery = """
					SELECT COUNT(e) FROM QueueEntry e
					WHERE e.queue.id = :queueId
					AND e.organization.id = :organizationId
					AND (:customerId IS NULL OR e.customer.id = :customerId)
					AND (:status IS NULL OR e.status = :status)
					AND (:priority IS NULL OR e.priority = :priority)
					""")
	Page<QueueEntry> search(@Param("queueId") UUID queueId,
			@Param("organizationId") UUID organizationId,
			@Param("customerId") UUID customerId,
			@Param("status") QueueEntryStatus status,
			@Param("priority") QueuePriority priority,
			Pageable pageable);
}
