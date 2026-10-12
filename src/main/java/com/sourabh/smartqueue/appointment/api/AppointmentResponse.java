package com.sourabh.smartqueue.appointment.api;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.sourabh.smartqueue.appointment.Appointment;

/**
 * Public appointment view (see ADR-026).
 *
 * <p>API-level data only: UUIDs, ISO-8601 instants, the branch IANA zone
 * for client-side interpretation, branch/service display names (added
 * for list readability in Phase 4I — rows previously could not name
 * the branch without per-row fetches), the snapshotted duration in
 * whole minutes (history-proof against later service changes), status
 * name, audit instants. The customer id is included because every
 * reader of this view is already authorized to see it (owner or
 * operational scope). Explicit manual mapping, no framework.
 */
public record AppointmentResponse(
		UUID id,
		UUID organizationId,
		UUID branchId,
		String branchTimeZone,
		String branchName,
		UUID serviceId,
		String serviceName,
		UUID customerId,
		Instant startAt,
		Instant endAt,
		int durationMinutes,
		String status,
		Instant createdAt,
		Instant updatedAt) {

	public static AppointmentResponse from(Appointment appointment) {
		return new AppointmentResponse(
				appointment.getId(),
				appointment.getOrganization().getId(),
				appointment.getBranch().getId(),
				appointment.getBranch().getTimeZone().getId(),
				appointment.getBranch().getName(),
				appointment.getService().getId(),
				appointment.getService().getName(),
				appointment.getCustomer().getId(),
				appointment.getStartAt(),
				appointment.getEndAt(),
				Math.toIntExact(Duration.between(
						appointment.getStartAt(), appointment.getEndAt()).toMinutes()),
				appointment.getStatus().name(),
				appointment.getCreatedAt(),
				appointment.getUpdatedAt());
	}
}
