package com.sourabh.smartqueue.appointment;

/**
 * The requested slot collides with an existing active appointment — the
 * application-facing translation of a PostgreSQL exclusion violation.
 *
 * <p>Carries no database details: callers learn that the slot is taken,
 * not which constraint fired.
 */
public class AppointmentConflictException extends RuntimeException {

	public AppointmentConflictException(String message) {
		super(message);
	}

	public AppointmentConflictException(String message, Throwable cause) {
		super(message, cause);
	}
}
