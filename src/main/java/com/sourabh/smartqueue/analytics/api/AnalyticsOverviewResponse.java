package com.sourabh.smartqueue.analytics.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Operational analytics overview (see ADR-035).
 *
 * <p>API-level data only: effective UTC window, effective timezone,
 * applied filters, and aggregated counts/durations. Counts are exact
 * row counts in the window ({@code 0} when nothing happened);
 * averages are whole-minute doubles and {@code null} when there were
 * no observations ({@code null} means "nothing observed", never
 * "zero"). No entities, no projections, no raw rows — every number
 * below is computed by PostgreSQL aggregation in the analytics query
 * layer, never by Java iteration over loaded collections.
 *
 * <p>Status splits always sum to their totals (every row carries
 * exactly one current status): appointment splits sum to
 * {@code appointments.total}, entry splits sum to
 * {@code queue.joined}, and {@code sessions.total = completed +
 * running}. Current status is reported exactly as stored — the
 * response makes no claim about historical transitions (see ADR-035).
 */
public record AnalyticsOverviewResponse(
		Instant from,
		Instant to,
		String timezone,
		UUID branchId,
		UUID serviceId,
		AppointmentMetrics appointments,
		QueueMetrics queue,
		SessionMetrics sessions,
		List<ServiceBreakdownRow> serviceBreakdown,
		List<BranchBreakdownRow> branchBreakdown) {

	/**
	 * Appointment activity by {@code startAt} in {@code [from, to)}.
	 * {@code total} counts every appointment starting in the window;
	 * the splits count current statuses and sum to it.
	 */
	public record AppointmentMetrics(
			long total,
			long scheduled,
			long confirmed,
			long completed,
			long cancelled,
			long noShow) {
	}

	/**
	 * Walk-in queue activity by {@code joinedAt} in {@code [from, to)}.
	 * {@code joined} counts every entry that joined in the window; the
	 * splits count current statuses and sum to it.
	 * {@code averageWaitMinutes} is the mean of
	 * {@code session.startedAt − entry.joinedAt} over COMPLETED entries
	 * joined in the window that have a service session ({@code null}
	 * when there are none) — measured history, never the Phase 2I
	 * live estimate.
	 */
	public record QueueMetrics(
			long joined,
			long waiting,
			long called,
			long serving,
			long completed,
			long cancelled,
			long noShow,
			Double averageWaitMinutes) {
	}

	/**
	 * Service sessions by {@code startedAt} in {@code [from, to)}.
	 * {@code total} counts sessions started in the window (running
	 * included); {@code completed} counts those with {@code endedAt}
	 * set. Durations cover ended sessions only — a running session
	 * contributes count but never a fake duration ({@code null} when
	 * no session ended).
	 */
	public record SessionMetrics(
			long total,
			long completed,
			Double averageDurationMinutes,
			Double totalDurationMinutes) {
	}

	/**
	 * One service in the selected scope. Ordered by operational volume
	 * ({@code appointmentCount + queueEntryCount}, highest first) with
	 * {@code serviceName} as the stable tie-break. Present exactly
	 * when the service has activity in at least one of the three
	 * aggregates; a service filter pins this list to that service.
	 */
	public record ServiceBreakdownRow(
			UUID serviceId,
			String serviceName,
			long appointmentCount,
			long completedAppointmentCount,
			long queueEntryCount,
			long completedSessionCount,
			Double averageServiceDurationMinutes) {
	}

	/**
	 * One branch in the selected scope (organization-wide reports;
	 * exactly the selected branch when filtered). Ordered like the
	 * service breakdown.
	 */
	public record BranchBreakdownRow(
			UUID branchId,
			String branchName,
			long appointmentCount,
			long completedAppointmentCount,
			long queueEntryCount,
			long completedSessionCount) {
	}
}
