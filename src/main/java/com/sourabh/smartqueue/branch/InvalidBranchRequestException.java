package com.sourabh.smartqueue.branch;

/**
 * A branch request the service cannot honor (see ADR-023).
 *
 * <p>Service-level input faults past Bean Validation's reach — chiefly an
 * unparseable IANA {@code ZoneId}. Renders as {@code 400
 * INVALID_BRANCH_REQUEST}. Uniqueness conflicts have their own
 * {@link BranchNameConflictException}; scope faults stay scope-safe
 * {@code 404}s.
 */
public class InvalidBranchRequestException extends RuntimeException {

	public InvalidBranchRequestException(String message) {
		super(message);
	}
}
