package com.sourabh.smartqueue.staff;

/**
 * An assignment linking rows of different organizations was requested
 * (see ADR-025).
 *
 * <p>User from A with branch from B can never exist: the application
 * rejects it with {@code 422 STAFF_ASSIGNMENT_BRANCH_MISMATCH} before
 * the database, whose composite foreign keys remain the backstop no code
 * path can bypass. Cross-organization rows never reach this check — they
 * are scope-safe {@code 404}s first.
 */
public class StaffAssignmentBranchMismatchException extends RuntimeException {

	public StaffAssignmentBranchMismatchException(String message) {
		super(message);
	}
}
