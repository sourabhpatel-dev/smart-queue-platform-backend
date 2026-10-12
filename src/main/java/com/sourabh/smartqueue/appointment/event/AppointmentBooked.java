package com.sourabh.smartqueue.appointment.event;

import java.time.Instant;
import java.util.UUID;

import com.sourabh.smartqueue.appointment.AppointmentStatus;

/**
 * An appointment was booked: a {@code [startAt, endAt)} interval is now held
 * for the customer (see ADR-016).
 *
 * <p>Fact, not command. IDs and interval snapshots only — never entities —
 * so future consumers (reminders, notifications, queue check-in) need no
 * persistence access to understand it. No cancelled/completed appointment
 * events exist yet: no service flow performs those transitions today, and
 * events are only published for operations that exist.
 */
public record AppointmentBooked(
		UUID eventId,
		Instant occurredAt,
		UUID organizationId,
		UUID branchId,
		UUID appointmentId,
		UUID serviceId,
		UUID customerId,
		Instant startAt,
		Instant endAt,
		AppointmentStatus status) {
}
