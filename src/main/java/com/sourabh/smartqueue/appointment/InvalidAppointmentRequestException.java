package com.sourabh.smartqueue.appointment;

/**
 * An appointment request failed validation (unknown ids, inactive branch /
 * service / customer, cross-organization mismatch, empty interval).
 *
 * <p>Distinct from {@link AppointmentConflictException}: the request itself
 * is invalid, not merely contended.
 */
public class InvalidAppointmentRequestException extends RuntimeException {

	public InvalidAppointmentRequestException(String message) {
		super(message);
	}
}
