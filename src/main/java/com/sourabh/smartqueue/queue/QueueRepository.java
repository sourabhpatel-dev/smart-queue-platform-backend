package com.sourabh.smartqueue.queue;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for {@link Queue}.
 *
 * <p>Intentionally almost bare: the call service resolves servable queues
 * through counter capabilities with a single ordered claim query instead.
 * The one derived-state exception is the refresh discovery below — it
 * returns distinct queue ids only (never entities), so the scheduled
 * freshness pass (ADR-020) never loads the database into Java.
 */
public interface QueueRepository extends JpaRepository<Queue, UUID> {

	/**
	 * Queues of one branch (one row per service by the V7 unique).
	 * Bounded by the branch's service catalog — a plain list, never a
	 * page. Callers scope the branch first; the organization guard below
	 * stays the cross-tenant rule for single-row loads.
	 */
	List<Queue> findByBranchId(UUID branchId);

	/**
	 * The single queue for a branch+service pair (empty when the pair
	 * has no provisioned queue). Served by the
	 * {@code uq_queue_branch_service} unique index — no extra index.
	 * Used by auto-provisioning (ADR-038): the existence check before
	 * the insert, with the unique constraint as the final backstop.
	 */
	Optional<Queue> findByBranchIdAndServiceId(UUID branchId, UUID serviceId);

	/**
	 * Ids of queues holding live entries: {@code ACTIVE} queues with at
	 * least one {@code WAITING}, {@code CALLED} or {@code SERVING} entry.
	 * Empty queues (nothing to display) and inactive queues (history only)
	 * are excluded — refreshing them would be perpetual work with no live
	 * state to keep fresh. Their Redis snapshots, if any, simply expire.
	 */
	@Query("""
			SELECT DISTINCT e.queue.id FROM QueueEntry e
			WHERE e.queue.status = :queueStatus
			  AND e.status IN :entryStatuses
			""")
	List<UUID> findIdsRequiringRealtimeRefresh(
			@Param("queueStatus") QueueStatus queueStatus,
			@Param("entryStatuses") Collection<QueueEntryStatus> entryStatuses);

	/**
	 * Scope-aware load: returns the queue only when it belongs to the given
	 * organization. Used by tenant-scoped application services (ADR-019) so
	 * a cross-tenant id yields empty (not-found) rather than a foreign
	 * entity — the caller cannot distinguish "missing" from "forbidden".
	 */
	Optional<Queue> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
