package com.sourabh.smartqueue.appointment;

/**
 * A booking naming a branch that does not own the service (see ADR-026).
 *
 * <p>The branch comes from the path and the service from the body; when
 * both live in the caller's organization but disagree, the combination is
 * well-formed yet invariant-violating — hence {@code 422
 * APPOINTMENT_BRANCH_MISMATCH}, following the capability precedent.
 * Foreign-organization rows never reach this check (scope-safe
 * {@code 404}s first); the V6 composite foreign keys remain the backstop.
 */
public class AppointmentBranchMismatchException extends RuntimeException {

	public AppointmentBranchMismatchException(String message) {
		super(message);
	}
}
