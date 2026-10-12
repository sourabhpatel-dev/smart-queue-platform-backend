package com.sourabh.smartqueue.appointment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sourabh.smartqueue.appointment.reminder.AppointmentReminderType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for {@link Appointment}.
 *
 * <p>Booking conflicts are enforced by the database exclusion constraints,
 * not by pre-check queries. The scope-aware finder serves tenant-scoped
 * loads (ADR-019): cross-tenant ids yield empty rather than foreign
 * entities. The conjunctive search serves the Phase 3K listing (ADR-026):
 * one query for every filter combination — each facet is nullable, and
 * the time window selects overlapping intervals
 * ({@code endAt > from AND startAt < to}), so ongoing appointments are
 * never cut off at the edges. Pagination caps the cost; no unbounded
 * reads exist.
 *
 * <p>Null-window mechanics: PostgreSQL cannot infer a type for a bare
 * null {@code Instant} bind in {@code (? IS NULL OR …)}, so the window
 * edges travel with boolean presence flags (the instant itself always
 * binds non-null — {@code EPOCH}/far-future dummies when absent — and
 * the flag short-circuits). UUID/enum facets need no flags: their column
 * comparisons type them.
 */
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

	/**
	 * Scope-aware load: returns the appointment only when it belongs to the
	 * given organization. Cross-tenant ids yield empty (not-found) rather
	 * than a foreign entity.
	 */
	Optional<Appointment> findByIdAndOrganizationId(UUID id, UUID organizationId);

	@Query(value = """
			SELECT a FROM Appointment a
			WHERE a.organization.id = :organizationId
			AND (:customerId IS NULL OR a.customer.id = :customerId)
			AND (:branchId IS NULL OR a.branch.id = :branchId)
			AND (:serviceId IS NULL OR a.service.id = :serviceId)
			AND (:status IS NULL OR a.status = :status)
			AND (:noFrom = TRUE OR a.endAt > :from)
			AND (:noTo = TRUE OR a.startAt < :to)
			""",
			countQuery = """
					SELECT COUNT(a) FROM Appointment a
					WHERE a.organization.id = :organizationId
					AND (:customerId IS NULL OR a.customer.id = :customerId)
					AND (:branchId IS NULL OR a.branch.id = :branchId)
					AND (:serviceId IS NULL OR a.service.id = :serviceId)
					AND (:status IS NULL OR a.status = :status)
					AND (:noFrom = TRUE OR a.endAt > :from)
					AND (:noTo = TRUE OR a.startAt < :to)
					""")
	Page<Appointment> search(@Param("organizationId") UUID organizationId,
			@Param("customerId") UUID customerId,
			@Param("branchId") UUID branchId,
			@Param("serviceId") UUID serviceId,
			@Param("status") AppointmentStatus status,
			@Param("noFrom") boolean noFrom,
			@Param("from") Instant from,
			@Param("noTo") boolean noTo,
			@Param("to") Instant to,
			Pageable pageable);

	/**
	 * Customer-scoped listing for platform-level customers (ADR-040):
	 * same conjunctive facets as {@link #search}, but <strong>no
	 * organization filter</strong> — a customer owns rows across any
	 * number of organizations. Safe precisely because the caller always
	 * pins {@code customerId} to the principal: the customer, never an
	 * organization, is the security boundary here. Operational listings
	 * keep using the organization-scoped {@link #search}.
	 */
	@Query(value = """
			SELECT a FROM Appointment a
			WHERE a.customer.id = :customerId
			AND (:branchId IS NULL OR a.branch.id = :branchId)
			AND (:serviceId IS NULL OR a.service.id = :serviceId)
			AND (:status IS NULL OR a.status = :status)
			AND (:noFrom = TRUE OR a.endAt > :from)
			AND (:noTo = TRUE OR a.startAt < :to)
			""",
			countQuery = """
					SELECT COUNT(a) FROM Appointment a
					WHERE a.customer.id = :customerId
					AND (:branchId IS NULL OR a.branch.id = :branchId)
					AND (:serviceId IS NULL OR a.service.id = :serviceId)
					AND (:status IS NULL OR a.status = :status)
					AND (:noFrom = TRUE OR a.endAt > :from)
					AND (:noTo = TRUE OR a.startAt < :to)
					""")
	Page<Appointment> searchForCustomer(@Param("customerId") UUID customerId,
			@Param("branchId") UUID branchId,
			@Param("serviceId") UUID serviceId,
			@Param("status") AppointmentStatus status,
			@Param("noFrom") boolean noFrom,
			@Param("from") Instant from,
			@Param("noTo") boolean noTo,
			@Param("to") Instant to,
			Pageable pageable);

	/**
	 * Bounded active-overlap read for availability (see ADR-039): live
	 * appointments ({@code SCHEDULED}/{@code CONFIRMED} only — terminal
	 * rows never block) overlapping the half-open window
	 * ({@code endAt > from AND startAt < to}, same edge semantics as
	 * the listing search, so boundary-touching neighbors are
	 * included-excluded correctly). Either dimension is skippable by
	 * null (service conflicts pin the service, customer conflicts pin
	 * the customer — the exclusion constraints are per service and
	 * per customer respectively). Window-bounded (one branch-local
	 * day in practice); ordered for deterministic consumption. The
	 * exclusions stay authoritative — this read only pre-filters what
	 * booking re-arbitrates.
	 */
	@Query("""
			SELECT a FROM Appointment a
			WHERE a.organization.id = :organizationId
			AND a.status IN :active
			AND (:serviceId IS NULL OR a.service.id = :serviceId)
			AND (:customerId IS NULL OR a.customer.id = :customerId)
			AND a.endAt > :from AND a.startAt < :to
			ORDER BY a.startAt ASC
			""")
	List<Appointment> findActiveOverlapping(
			@Param("organizationId") UUID organizationId,
			@Param("serviceId") UUID serviceId,
			@Param("customerId") UUID customerId,
			@Param("active") Collection<AppointmentStatus> active,
			@Param("from") Instant from,
			@Param("to") Instant to);

	/**
	 * Bounded reminder discovery (see ADR-034): active appointments
	 * starting in {@code (now, horizon]} that have no reminder row of
	 * the given type yet, oldest start first. The {@code startAt}
	 * window is what bounds the sweep — {@code horizon = now + lead},
	 * so the range is at most one lead duration wide and long-past
	 * appointments can never cause work. The window edges are plain
	 * instants (the query performs no duration arithmetic), which
	 * keeps the {@code (status, start_at)} index usable.
	 *
	 * <p>Candidates only: per-row eligibility (status re-check against
	 * the committed row, tenant agreement) is finalized inside each
	 * candidate's own transaction, where a concurrent cancellation is
	 * visible.
	 */
	@Query("""
			SELECT a FROM Appointment a
			WHERE a.status IN :active
			AND a.startAt > :now
			AND a.startAt <= :horizon
			AND NOT EXISTS (
				SELECT 1 FROM com.sourabh.smartqueue.appointment.reminder.AppointmentReminder r
				WHERE r.appointment.id = a.id AND r.reminderType = :type
			)
			ORDER BY a.startAt ASC
			""")
	List<Appointment> findReminderCandidates(
			@Param("active") Collection<AppointmentStatus> active,
			@Param("now") Instant now,
			@Param("horizon") Instant horizon,
			@Param("type") AppointmentReminderType type,
			Pageable pageable);
}
