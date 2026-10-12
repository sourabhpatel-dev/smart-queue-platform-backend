package com.sourabh.smartqueue.counter;

/**
 * The counter already serves the service (see ADR-024).
 *
 * <p>Application translation of {@code uq_capability_service_counter}:
 * concurrent adds serialize on the constraint; the API renders {@code 409
 * CAPABILITY_ALREADY_EXISTS} with no SQL details. Re-adding is a client
 * error worth signaling (unlike the idempotent state commands) because
 * the association either exists or it does not.
 */
public class CapabilityAlreadyExistsException extends RuntimeException {

	public CapabilityAlreadyExistsException(String message) {
		super(message);
	}
}
