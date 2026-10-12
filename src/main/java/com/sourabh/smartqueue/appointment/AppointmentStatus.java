package com.sourabh.smartqueue.appointment;

/**
 * Lifecycle states for an Appointment (see ADR-011).
 *
 * <p>Only {@code SCHEDULED} and {@code CONFIRMED} occupy a booking slot —
 * the database exclusion constraints ignore every other status, so
 * cancelled, completed, and no-show appointments never block future
 * bookings. Persisted by name, never by ordinal.
 */
public enum AppointmentStatus {
	SCHEDULED,
	CONFIRMED,
	COMPLETED,
	CANCELLED,
	NO_SHOW
}
