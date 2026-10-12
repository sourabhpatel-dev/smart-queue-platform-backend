package com.sourabh.smartqueue.analytics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sourabh.smartqueue.appointment.Appointment;
import com.sourabh.smartqueue.appointment.AppointmentStatus;
import com.sourabh.smartqueue.queue.QueueEntryStatus;

/**
 * Read-only analytics query layer (see ADR-035).
 *
 * <p>Every method is a database aggregation — counts, sums, and
 * averages computed by PostgreSQL, never by Java iteration over
 * loaded collections. No method returns an entity or a managed
 * collection: scalars and small group-by rows only, so there is no
 * N+1 by construction and memory stays proportional to the number of
 * statuses/services/branches in one organization, never to row
 * volume.
 *
 * <p>Scope discipline: every query carries the identical scope —
 * organization (always, from the principal, never from the client),
 * optional branch, optional service, and the effective
 * {@code [from, to)} instant window. Summary and breakdowns therefore
 * cannot drift apart: they filter the same rows by the same
 * predicates. UUID/enum facets use the established {@code IS NULL}
 * pattern (their column comparisons type them); {@code from}/{@code
 * to} are never null (the service resolves them before calling).
 *
 * <p>Duration math is PostgreSQL-specific by decision:
 * {@code AVG(EXTRACT(EPOCH FROM end) − EXTRACT(EPOCH FROM start))}
 * renders PG SQL through HQL and returns seconds as a {@code Double}
 * ({@code null} when there are no rows — never zero, never NaN).
 * The two-sided form is deliberate: Hibernate types
 * {@code end − start} as a {@code Duration}, which its
 * {@code extract()} rejects (found by failing query validation —
 * {@code EXTRACT} demands a temporal argument), while extracting
 * each endpoint first is arithmetically identical. HQL keeps the
 * parameters type-safe (a bare native query would reintroduce the
 * null-bind typing problem ADR-026 documents); H2 is not a supported
 * database in this project, so PG-only SQL functions are acceptable.
 * Estimated Phase 2I waits appear nowhere here — every average below
 * derives from stored timestamps only.
 */
public interface AnalyticsRepository extends JpaRepository<Appointment, UUID> {

	// -- Appointments (anchor: startAt) -----------------------------------

	/**
	 * Appointment counts per current status for appointments starting
	 * in {@code [from, to)}. Current status exactly as stored — no
	 * historical transition is claimed (see ADR-035 §18).
	 */
	@Query("""
			SELECT a.status, COUNT(a) FROM Appointment a
			WHERE a.organization.id = :organizationId
			AND (:branchId IS NULL OR a.branch.id = :branchId)
			AND (:serviceId IS NULL OR a.service.id = :serviceId)
			AND a.startAt >= :from
			AND a.startAt < :to
			GROUP BY a.status
			""")
	List<Object[]> countAppointmentsByStatus(
			@Param("organizationId") UUID organizationId,
			@Param("branchId") UUID branchId,
			@Param("serviceId") UUID serviceId,
			@Param("from") Instant from,
			@Param("to") Instant to);

	@Query("""
			SELECT a.service.id, a.service.name, COUNT(a),
				SUM(CASE WHEN a.status = :completed THEN 1 ELSE 0 END)
			FROM Appointment a
			WHERE a.organization.id = :organizationId
			AND (:branchId IS NULL OR a.branch.id = :branchId)
			AND (:serviceId IS NULL OR a.service.id = :serviceId)
			AND a.startAt >= :from
			AND a.startAt < :to
			GROUP BY a.service.id, a.service.name
			""")
	List<Object[]> countAppointmentsByService(
			@Param("organizationId") UUID organizationId,
			@Param("branchId") UUID branchId,
			@Param("serviceId") UUID serviceId,
			@Param("completed") AppointmentStatus completed,
			@Param("from") Instant from,
			@Param("to") Instant to);

	@Query("""
			SELECT a.branch.id, a.branch.name, COUNT(a),
				SUM(CASE WHEN a.status = :completed THEN 1 ELSE 0 END)
			FROM Appointment a
			WHERE a.organization.id = :organizationId
			AND (:branchId IS NULL OR a.branch.id = :branchId)
			AND (:serviceId IS NULL OR a.service.id = :serviceId)
			AND a.startAt >= :from
			AND a.startAt < :to
			GROUP BY a.branch.id, a.branch.name
			""")
	List<Object[]> countAppointmentsByBranch(
			@Param("organizationId") UUID organizationId,
			@Param("branchId") UUID branchId,
			@Param("serviceId") UUID serviceId,
			@Param("completed") AppointmentStatus completed,
			@Param("from") Instant from,
			@Param("to") Instant to);

	// -- Queue entries (anchor: joinedAt) ----------------------------------

	/**
	 * Queue-entry counts per current status for entries that joined in
	 * {@code [from, to)}. Walk-in activity is anchored on the join —
	 * never on appointment start times.
	 */
	@Query("""
			SELECT e.status, COUNT(e) FROM QueueEntry e
			WHERE e.organization.id = :organizationId
			AND (:branchId IS NULL OR e.queue.branch.id = :branchId)
			AND (:serviceId IS NULL OR e.queue.service.id = :serviceId)
			AND e.joinedAt >= :from
			AND e.joinedAt < :to
			GROUP BY e.status
			""")
	List<Object[]> countEntriesByStatus(
			@Param("organizationId") UUID organizationId,
			@Param("branchId") UUID branchId,
			@Param("serviceId") UUID serviceId,
			@Param("from") Instant from,
			@Param("to") Instant to);

	@Query("""
			SELECT e.queue.service.id, e.queue.service.name, COUNT(e)
			FROM QueueEntry e
			WHERE e.organization.id = :organizationId
			AND (:branchId IS NULL OR e.queue.branch.id = :branchId)
			AND (:serviceId IS NULL OR e.queue.service.id = :serviceId)
			AND e.joinedAt >= :from
			AND e.joinedAt < :to
			GROUP BY e.queue.service.id, e.queue.service.name
			""")
	List<Object[]> countEntriesByService(
			@Param("organizationId") UUID organizationId,
			@Param("branchId") UUID branchId,
			@Param("serviceId") UUID serviceId,
			@Param("from") Instant from,
			@Param("to") Instant to);

	@Query("""
			SELECT e.queue.branch.id, e.queue.branch.name, COUNT(e)
			FROM QueueEntry e
			WHERE e.organization.id = :organizationId
			AND (:branchId IS NULL OR e.queue.branch.id = :branchId)
			AND (:serviceId IS NULL OR e.queue.service.id = :serviceId)
			AND e.joinedAt >= :from
			AND e.joinedAt < :to
			GROUP BY e.queue.branch.id, e.queue.branch.name
			""")
	List<Object[]> countEntriesByBranch(
			@Param("organizationId") UUID organizationId,
			@Param("branchId") UUID branchId,
			@Param("serviceId") UUID serviceId,
			@Param("from") Instant from,
			@Param("to") Instant to);

	/**
	 * Mean actual wait in seconds over COMPLETED entries joined in
	 * {@code [from, to)} that have a service session:
	 * {@code session.startedAt − entry.joinedAt}. The inner join is
	 * the definition — an entry without a session contributes no wait
	 * (it never reached service). Sessions lacking an end still
	 * contribute their wait (waiting ended when service started);
	 * only the status gate and the join decide membership. {@code null}
	 * when no completed entry with a session exists.
	 */
	@Query("""
			SELECT AVG(EXTRACT(EPOCH FROM s.startedAt) - EXTRACT(EPOCH FROM e.joinedAt))
			FROM QueueEntry e, ServiceSession s
			WHERE s.queueEntry.id = e.id
			AND e.organization.id = :organizationId
			AND (:branchId IS NULL OR e.queue.branch.id = :branchId)
			AND (:serviceId IS NULL OR e.queue.service.id = :serviceId)
			AND e.status = :completed
			AND e.joinedAt >= :from
			AND e.joinedAt < :to
			""")
	Double averageWaitSeconds(
			@Param("organizationId") UUID organizationId,
			@Param("branchId") UUID branchId,
			@Param("serviceId") UUID serviceId,
			@Param("completed") QueueEntryStatus completed,
			@Param("from") Instant from,
			@Param("to") Instant to);

	// -- Service sessions (anchor: startedAt) -------------------------------

	/**
	 * One {@code [total started in window, ended among them]} row —
	 * an aggregate without {@code GROUP BY} always yields exactly one
	 * row, so callers read index {@code 0} (a declared {@code Object[]}
	 * return would instead receive one element per row — Spring Data
	 * packs row lists into arrays). Running sessions count in the
	 * first element only — duration aggregates below deliberately
	 * exclude them.
	 */
	@Query("""
			SELECT COUNT(s),
				SUM(CASE WHEN s.endedAt IS NOT NULL THEN 1 ELSE 0 END)
			FROM ServiceSession s
			WHERE s.branch.organization.id = :organizationId
			AND (:branchId IS NULL OR s.branch.id = :branchId)
			AND (:serviceId IS NULL OR s.service.id = :serviceId)
			AND s.startedAt >= :from
			AND s.startedAt < :to
			""")
	List<Object[]> countSessions(
			@Param("organizationId") UUID organizationId,
			@Param("branchId") UUID branchId,
			@Param("serviceId") UUID serviceId,
			@Param("from") Instant from,
			@Param("to") Instant to);

	/**
	 * One {@code [average seconds, total seconds]} row over sessions
	 * started in the window that have ended ({@code endedAt −
	 * startedAt}, valid by the V8 range check). Both elements are
	 * {@code null} when no session ended — never zero, never a fake
	 * running-session duration.
	 */
	@Query("""
			SELECT AVG(EXTRACT(EPOCH FROM s.endedAt) - EXTRACT(EPOCH FROM s.startedAt)),
				SUM(EXTRACT(EPOCH FROM s.endedAt) - EXTRACT(EPOCH FROM s.startedAt))
			FROM ServiceSession s
			WHERE s.branch.organization.id = :organizationId
			AND (:branchId IS NULL OR s.branch.id = :branchId)
			AND (:serviceId IS NULL OR s.service.id = :serviceId)
			AND s.endedAt IS NOT NULL
			AND s.startedAt >= :from
			AND s.startedAt < :to
			""")
	List<Object[]> sessionDurationStats(
			@Param("organizationId") UUID organizationId,
			@Param("branchId") UUID branchId,
			@Param("serviceId") UUID serviceId,
			@Param("from") Instant from,
			@Param("to") Instant to);

	@Query("""
			SELECT s.service.id, s.service.name, COUNT(s),
				AVG(EXTRACT(EPOCH FROM s.endedAt) - EXTRACT(EPOCH FROM s.startedAt))
			FROM ServiceSession s
			WHERE s.branch.organization.id = :organizationId
			AND (:branchId IS NULL OR s.branch.id = :branchId)
			AND (:serviceId IS NULL OR s.service.id = :serviceId)
			AND s.endedAt IS NOT NULL
			AND s.startedAt >= :from
			AND s.startedAt < :to
			GROUP BY s.service.id, s.service.name
			""")
	List<Object[]> completedSessionsByService(
			@Param("organizationId") UUID organizationId,
			@Param("branchId") UUID branchId,
			@Param("serviceId") UUID serviceId,
			@Param("from") Instant from,
			@Param("to") Instant to);

	@Query("""
			SELECT s.branch.id, s.branch.name, COUNT(s)
			FROM ServiceSession s
			WHERE s.branch.organization.id = :organizationId
			AND (:branchId IS NULL OR s.branch.id = :branchId)
			AND (:serviceId IS NULL OR s.service.id = :serviceId)
			AND s.endedAt IS NOT NULL
			AND s.startedAt >= :from
			AND s.startedAt < :to
			GROUP BY s.branch.id, s.branch.name
			""")
	List<Object[]> completedSessionsByBranch(
			@Param("organizationId") UUID organizationId,
			@Param("branchId") UUID branchId,
			@Param("serviceId") UUID serviceId,
			@Param("from") Instant from,
			@Param("to") Instant to);
}
