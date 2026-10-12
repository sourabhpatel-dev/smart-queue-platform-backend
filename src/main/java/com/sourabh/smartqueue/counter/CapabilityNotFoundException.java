package com.sourabh.smartqueue.counter;

/**
 * No such capability on the caller's counter (see ADR-024).
 *
 * <p>Renders as scope-safe {@code 404 CAPABILITY_NOT_FOUND}: removing an
 * association that is not there signals the client's stale view instead
 * of silently succeeding. Removes nothing else — never the service, the
 * counter, or any appointment/queue/session row.
 */
public class CapabilityNotFoundException extends RuntimeException {

	public CapabilityNotFoundException(String message) {
		super(message);
	}
}
