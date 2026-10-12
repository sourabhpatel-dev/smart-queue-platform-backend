package com.sourabh.smartqueue.appointment;

/**
 * A lifecycle command the domain refuses (see ADR-026).
 *
 * <p>Application translation of the entity's guarded transitions:
 * leaving the active states is one-way, so cancelling/completing/
 * marking no-show on a terminal appointment fails. Unlike the branch
 * state commands, these are deliberately NOT idempotent — the domain
 * rule wins and the API renders {@code 409
 * APPOINTMENT_INVALID_TRANSITION} instead of silently succeeding.
 */
public class AppointmentInvalidTransitionException extends RuntimeException {

	public AppointmentInvalidTransitionException(String message) {
		super(message);
	}
}
