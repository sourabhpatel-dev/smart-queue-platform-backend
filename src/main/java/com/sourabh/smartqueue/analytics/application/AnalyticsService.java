package com.sourabh.smartqueue.analytics.application;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.analytics.AnalyticsProperties;
import com.sourabh.smartqueue.analytics.AnalyticsRepository;
import com.sourabh.smartqueue.analytics.AnalyticsScopeMismatchException;
import com.sourabh.smartqueue.analytics.api.AnalyticsOverviewResponse;
import com.sourabh.smartqueue.appointment.AppointmentStatus;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.TenantAccessPolicy;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.queue.QueueEntryStatus;

/**
 * Operational analytics use cases (see ADR-035).
 *
 * <p>Read-only orchestration over the analytics query layer: role
 * gate, tenant-safe scope resolution, range/timezone resolution into
 * one effective {@code [from, to)} instant window, then aggregation
 * calls that all share exactly that scope, then DTO assembly. No
 * method here mutates anything — reporting reads committed
 * PostgreSQL state and returns it.
 *
 * <p>Scope rules, in order (deny by default, existing architecture
 * reused — no controller role logic, no new policy):
 *
 * <ol>
 *   <li>Management roles only ({@code MANAGER}/{@code ADMIN}) via
 *   {@link TenantAccessPolicy#requireOrganizationManagement} — staff
 *   and customers fail with the existing {@code 403}.</li>
 *   <li>Organization always comes from the principal — no
 *   organization id is accepted from the client, on any path.</li>
 *   <li>Branch/service filters are loaded and pinned to the
 *   principal's organization; unknown or foreign ids yield the same
 *   scope-safe {@code 404} (existence, metrics, and names of other
 *   tenants never leak). A same-organization service outside the
 *   requested branch is a {@code 422} mismatch, not an empty
 *   report.</li>
 * </ol>
 *
 * <p>Time rules: {@code from}/{@code to} arrive as local date-times
 * in the effective zone (explicit {@code timezone} wins; otherwise
 * the selected branch's zone; organization-wide reports without an
 * explicit zone are rejected — zones are never guessed, and the JVM
 * default zone is never consulted). Conversion uses
 * {@code LocalDateTime.atZone} semantics (a DST gap shifts forward,
 * an overlap takes the earlier offset — both documented in
 * ADR-035). {@code [from, to)} is enforced everywhere downstream:
 * {@code >= from AND < to}.
 */
@Service
public class AnalyticsService {

	static final String DATE_RANGE_CODE = "INVALID_DATE_RANGE";

	static final String TIMEZONE_CODE = "INVALID_TIMEZONE";

	private final AnalyticsRepository analytics;
	private final AnalyticsProperties policy;
	private final TenantAccessPolicy access;
	private final com.sourabh.smartqueue.branch.BranchRepository branches;
	private final com.sourabh.smartqueue.service.ServiceRepository services;

	public AnalyticsService(AnalyticsRepository analytics,
			AnalyticsProperties policy,
			TenantAccessPolicy access,
			com.sourabh.smartqueue.branch.BranchRepository branches,
			com.sourabh.smartqueue.service.ServiceRepository services) {
		this.analytics = analytics;
		this.policy = policy;
		this.access = access;
		this.branches = branches;
		this.services = services;
	}

	@Transactional(readOnly = true)
	public AnalyticsOverviewResponse overview(AuthenticatedPrincipal principal,
			LocalDateTime from, LocalDateTime to, String timezone,
			UUID branchId, UUID serviceId) {
		access.requireOrganizationManagement(principal);
		UUID organizationId = principal.organizationId();

		Branch branch = branchId == null ? null : scopedBranch(organizationId, branchId);
		com.sourabh.smartqueue.service.Service service =
				serviceId == null ? null : scopedService(organizationId, serviceId);
		if (branch != null && service != null
				&& !service.getBranch().getId().equals(branch.getId())) {
			throw new AnalyticsScopeMismatchException(
					"Service does not belong to the requested branch");
		}

		ZoneId zone = effectiveZone(timezone, branch);
		Instant fromInstant = toInstant(from, zone, "from");
		Instant toInstant = toInstant(to, zone, "to");
		if (!fromInstant.isBefore(toInstant)) {
			throw new InvalidRequestException(DATE_RANGE_CODE,
					"Reporting range must satisfy from < to ([from, to) semantics)");
		}
		Duration span = Duration.between(fromInstant, toInstant);
		if (span.compareTo(Duration.ofDays(policy.getMaxRangeDays())) > 0) {
			throw new InvalidRequestException(DATE_RANGE_CODE,
					"Reporting range must not exceed " + policy.getMaxRangeDays()
							+ " days; narrow from/to instead");
		}

		Map<AppointmentStatus, Long> appointments =
				statusCounts(analytics.countAppointmentsByStatus(
						organizationId, branchId, serviceId, fromInstant, toInstant));
		Map<QueueEntryStatus, Long> entries =
				statusCounts(analytics.countEntriesByStatus(
						organizationId, branchId, serviceId, fromInstant, toInstant));
		Double waitSeconds = analytics.averageWaitSeconds(organizationId, branchId,
				serviceId, QueueEntryStatus.COMPLETED, fromInstant, toInstant);
		Object[] sessionCounts = singleRow(analytics.countSessions(
				organizationId, branchId, serviceId, fromInstant, toInstant));
		Object[] durationStats = singleRow(analytics.sessionDurationStats(
				organizationId, branchId, serviceId, fromInstant, toInstant));

		long totalAppointments = sum(appointments);
		long joinedEntries = sum(entries);
		long totalSessions = toLong(sessionCounts[0]);
		long completedSessions = toLong(sessionCounts[1]);

		return new AnalyticsOverviewResponse(
				fromInstant, toInstant, zone.getId(), branchId, serviceId,
				new AnalyticsOverviewResponse.AppointmentMetrics(
						totalAppointments,
						appointments.getOrDefault(AppointmentStatus.SCHEDULED, 0L),
						appointments.getOrDefault(AppointmentStatus.CONFIRMED, 0L),
						appointments.getOrDefault(AppointmentStatus.COMPLETED, 0L),
						appointments.getOrDefault(AppointmentStatus.CANCELLED, 0L),
						appointments.getOrDefault(AppointmentStatus.NO_SHOW, 0L)),
				new AnalyticsOverviewResponse.QueueMetrics(
						joinedEntries,
						entries.getOrDefault(QueueEntryStatus.WAITING, 0L),
						entries.getOrDefault(QueueEntryStatus.CALLED, 0L),
						entries.getOrDefault(QueueEntryStatus.SERVING, 0L),
						entries.getOrDefault(QueueEntryStatus.COMPLETED, 0L),
						entries.getOrDefault(QueueEntryStatus.CANCELLED, 0L),
						entries.getOrDefault(QueueEntryStatus.NO_SHOW, 0L),
						toMinutes(waitSeconds)),
				new AnalyticsOverviewResponse.SessionMetrics(
						totalSessions,
						completedSessions,
						toMinutes(toDouble(durationStats[0])),
						toMinutes(toDouble(durationStats[1]))),
				serviceBreakdown(organizationId, branchId, serviceId, fromInstant, toInstant),
				branchBreakdown(organizationId, branchId, serviceId, fromInstant, toInstant));
	}

	private List<AnalyticsOverviewResponse.ServiceBreakdownRow> serviceBreakdown(
			UUID organizationId, UUID branchId, UUID serviceId,
			Instant from, Instant to) {
		Map<UUID, ServiceAccumulator> byService = new LinkedHashMap<>();
		for (Object[] row : analytics.countAppointmentsByService(organizationId,
				branchId, serviceId, AppointmentStatus.COMPLETED, from, to)) {
			ServiceAccumulator acc = accumulator(byService, row);
			acc.appointments = toLong(row[2]);
			acc.completedAppointments = toLong(row[3]);
		}
		for (Object[] row : analytics.countEntriesByService(
				organizationId, branchId, serviceId, from, to)) {
			accumulator(byService, row).entries = toLong(row[2]);
		}
		for (Object[] row : analytics.completedSessionsByService(
				organizationId, branchId, serviceId, from, to)) {
			ServiceAccumulator acc = accumulator(byService, row);
			acc.completedSessions = toLong(row[2]);
			acc.averageDurationSeconds = toDouble(row[3]);
		}
		return byService.values().stream()
				.sorted(volumeOrder())
				.map(acc -> new AnalyticsOverviewResponse.ServiceBreakdownRow(
						acc.id, acc.name, acc.appointments, acc.completedAppointments,
						acc.entries, acc.completedSessions,
						toMinutes(acc.averageDurationSeconds)))
				.toList();
	}

	private List<AnalyticsOverviewResponse.BranchBreakdownRow> branchBreakdown(
			UUID organizationId, UUID branchId, UUID serviceId,
			Instant from, Instant to) {
		Map<UUID, BranchAccumulator> byBranch = new LinkedHashMap<>();
		for (Object[] row : analytics.countAppointmentsByBranch(organizationId,
				branchId, serviceId, AppointmentStatus.COMPLETED, from, to)) {
			BranchAccumulator acc = branchAccumulator(byBranch, row);
			acc.appointments = toLong(row[2]);
			acc.completedAppointments = toLong(row[3]);
		}
		for (Object[] row : analytics.countEntriesByBranch(
				organizationId, branchId, serviceId, from, to)) {
			branchAccumulator(byBranch, row).entries = toLong(row[2]);
		}
		for (Object[] row : analytics.completedSessionsByBranch(
				organizationId, branchId, serviceId, from, to)) {
			branchAccumulator(byBranch, row).completedSessions = toLong(row[2]);
		}
		List<AnalyticsOverviewResponse.BranchBreakdownRow> rows = new ArrayList<>();
		for (BranchAccumulator acc : byBranch.values().stream()
				.sorted(volumeOrderBranch()).toList()) {
			rows.add(new AnalyticsOverviewResponse.BranchBreakdownRow(
					acc.id, acc.name, acc.appointments, acc.completedAppointments,
					acc.entries, acc.completedSessions));
		}
		return rows;
	}

	private static final class ServiceAccumulator {
		final UUID id;
		final String name;
		long appointments;
		long completedAppointments;
		long entries;
		long completedSessions;
		Double averageDurationSeconds;

		ServiceAccumulator(UUID id, String name) {
			this.id = id;
			this.name = name;
		}

		long volume() {
			return appointments + entries;
		}
	}

	private static final class BranchAccumulator {
		final UUID id;
		final String name;
		long appointments;
		long completedAppointments;
		long entries;
		long completedSessions;

		BranchAccumulator(UUID id, String name) {
			this.id = id;
			this.name = name;
		}

		long volume() {
			return appointments + entries;
		}
	}

	private static ServiceAccumulator accumulator(
			Map<UUID, ServiceAccumulator> map, Object[] row) {
		UUID id = (UUID) row[0];
		return map.computeIfAbsent(id,
				key -> new ServiceAccumulator(key, (String) row[1]));
	}

	private static BranchAccumulator branchAccumulator(
			Map<UUID, BranchAccumulator> map, Object[] row) {
		UUID id = (UUID) row[0];
		return map.computeIfAbsent(id,
				key -> new BranchAccumulator(key, (String) row[1]));
	}

	private static Comparator<ServiceAccumulator> volumeOrder() {
		return Comparator.comparingLong(ServiceAccumulator::volume).reversed()
				.thenComparing(acc -> acc.name);
	}

	private static Comparator<BranchAccumulator> volumeOrderBranch() {
		return Comparator.comparingLong(BranchAccumulator::volume).reversed()
				.thenComparing(acc -> acc.name);
	}

	private Branch scopedBranch(UUID organizationId, UUID branchId) {
		Branch branch = branches.findById(branchId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"BRANCH_NOT_FOUND", "Branch not found"));
		if (!branch.getOrganization().getId().equals(organizationId)) {
			throw new ResourceNotFoundException("BRANCH_NOT_FOUND", "Branch not found");
		}
		return branch;
	}

	private com.sourabh.smartqueue.service.Service scopedService(
			UUID organizationId, UUID serviceId) {
		com.sourabh.smartqueue.service.Service service = services.findById(serviceId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"SERVICE_NOT_FOUND", "Service not found"));
		if (!service.getBranch().getOrganization().getId().equals(organizationId)) {
			throw new ResourceNotFoundException("SERVICE_NOT_FOUND", "Service not found");
		}
		return service;
	}

	private ZoneId effectiveZone(String timezone, Branch branch) {
		if (timezone != null && !timezone.isBlank()) {
			try {
				return ZoneId.of(timezone.trim());
			} catch (DateTimeException e) {
				throw new InvalidRequestException(TIMEZONE_CODE,
						"Invalid time zone: " + timezone);
			}
		}
		if (branch != null) {
			return branch.getTimeZone();
		}
		throw new InvalidRequestException(TIMEZONE_CODE,
				"timezone is required for organization-wide reports (IANA ZoneId, e.g. Asia/Kolkata)");
	}

	private Instant toInstant(LocalDateTime value, ZoneId zone, String field) {
		if (value == null) {
			throw new InvalidRequestException(DATE_RANGE_CODE,
					"Reporting range requires both from and to (" + field + " is missing)");
		}
		return value.atZone(zone).toInstant();
	}

	private static Object[] singleRow(List<Object[]> rows) {
		if (rows == null || rows.isEmpty()) {
			throw new IllegalStateException(
					"aggregate query without GROUP BY returned no row");
		}
		return rows.get(0);
	}

	@SuppressWarnings("unchecked")
	private static <E extends Enum<E>> Map<E, Long> statusCounts(List<Object[]> rows) {
		Map<E, Long> counts = new LinkedHashMap<>();
		for (Object[] row : rows) {
			counts.merge((E) row[0], toLong(row[1]), Long::sum);
		}
		return counts;
	}

	private static long sum(Map<?, Long> counts) {
		return counts.values().stream().mapToLong(Long::longValue).sum();
	}

	private static long toLong(Object value) {
		return value == null ? 0L : ((Number) value).longValue();
	}

	private static Double toDouble(Object value) {
		return value == null ? null : ((Number) value).doubleValue();
	}

	private static Double toMinutes(Double seconds) {
		return seconds == null ? null : seconds / 60.0;
	}
}
