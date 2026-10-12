package com.sourabh.smartqueue.appointment.api;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Appointment booking payload (see ADR-026).
 *
 * <p>Two fields: which service and when it starts. Everything else is
 * server-derived or principal-derived: the branch comes from the path
 * (and must own the service), the customer is always the authenticated
 * principal (no delegated booking — no customer id is accepted from any
 * role), the end is snapshotted from the service duration, the status
 * starts {@code SCHEDULED}. The start is an ISO-8601 instant (offset
 * preserved); branch-local interpretation, holidays, working hours, DST,
 * and overlap arbitration all stay inside the booking service.
 */
public record CreateAppointmentRequest(
		@NotNull UUID serviceId,
		@NotNull Instant startAt) {
}
