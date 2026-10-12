package com.sourabh.smartqueue.queue;

/**
 * No such service session in the caller's organization (see ADR-027,
 * session reads).
 *
 * <p>Renders as scope-safe {@code 404 SERVICE_SESSION_NOT_FOUND}:
 * unknown ids and foreign-organization rows are indistinguishable — and
 * so is an idle counter: with no active session, the addressed
 * current-session sub-resource simply does not exist (which doubles as
 * the derived occupancy signal: {@code 200} means occupied,
 * {@code 404} means free).
 */
public class ServiceSessionNotFoundException extends RuntimeException {

	public ServiceSessionNotFoundException(String message) {
		super(message);
	}
}
