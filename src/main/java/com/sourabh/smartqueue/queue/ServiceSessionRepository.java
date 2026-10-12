package com.sourabh.smartqueue.queue;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for {@link ServiceSession}.
 *
 * <p>Entry lookup serves the lifecycle transitions; the counter and
 * conjunctive finders serve the Phase 3M operational reads. "Current"
 * means exactly what the domain means by running — {@code endedAt IS
 * NULL} — ordered newest-first so overlapping operations (which the
 * model tolerates) resolve deterministically to the latest. No new index:
 * V8 deliberately created none for counter reads, and correctness never
 * depends on one (a future volume phase may revisit with measurements).
 */
public interface ServiceSessionRepository extends JpaRepository<ServiceSession, UUID> {

	Optional<ServiceSession> findByQueueEntryId(UUID queueEntryId);

	Optional<ServiceSession> findFirstByCounterIdAndEndedAtIsNullOrderByStartedAtDesc(
			UUID counterId);

	/**
	 * Conjunctive session search: every facet nullable (all UUID/boolean
	 * typed, so the {@code IS NULL} checks plan cleanly); the started-at
	 * window travels with boolean presence flags because PostgreSQL cannot
	 * type a bare null {@code Instant} bind (see ADR-026).
	 */
	@Query(value = """
			SELECT s FROM ServiceSession s
			WHERE s.branch.organization.id = :organizationId
			AND (:branchId IS NULL OR s.branch.id = :branchId)
			AND (:counterId IS NULL OR s.counter.id = :counterId)
			AND (:serviceId IS NULL OR s.service.id = :serviceId)
			AND (:customerId IS NULL OR s.queueEntry.customer.id = :customerId)
			AND (:queueEntryId IS NULL OR s.queueEntry.id = :queueEntryId)
			AND (:active IS NULL OR (:active = TRUE AND s.endedAt IS NULL)
				OR (:active = FALSE AND s.endedAt IS NOT NULL))
			AND (:noFrom = TRUE OR s.startedAt >= :from)
			AND (:noTo = TRUE OR s.startedAt < :to)
			""",
			countQuery = """
					SELECT COUNT(s) FROM ServiceSession s
					WHERE s.branch.organization.id = :organizationId
					AND (:branchId IS NULL OR s.branch.id = :branchId)
					AND (:counterId IS NULL OR s.counter.id = :counterId)
					AND (:serviceId IS NULL OR s.service.id = :serviceId)
					AND (:customerId IS NULL OR s.queueEntry.customer.id = :customerId)
					AND (:queueEntryId IS NULL OR s.queueEntry.id = :queueEntryId)
					AND (:active IS NULL OR (:active = TRUE AND s.endedAt IS NULL)
						OR (:active = FALSE AND s.endedAt IS NOT NULL))
					AND (:noFrom = TRUE OR s.startedAt >= :from)
					AND (:noTo = TRUE OR s.startedAt < :to)
					""")
	Page<ServiceSession> search(@Param("organizationId") UUID organizationId,
			@Param("branchId") UUID branchId,
			@Param("counterId") UUID counterId,
			@Param("serviceId") UUID serviceId,
			@Param("customerId") UUID customerId,
			@Param("queueEntryId") UUID queueEntryId,
			@Param("active") Boolean active,
			@Param("noFrom") boolean noFrom,
			@Param("from") Instant from,
			@Param("noTo") boolean noTo,
			@Param("to") Instant to,
			Pageable pageable);
}
