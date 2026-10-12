package com.sourabh.smartqueue.staff;

/**
 * The user is already assigned to the branch (see ADR-025).
 *
 * <p>Application translation of {@code uq_staff_user_branch}: the unique
 * key ignores status, so even an INACTIVE row conflicts — reactivation
 * is the path back, not a second row. Concurrent creates serialize on the
 * constraint; the API renders {@code 409 STAFF_ASSIGNMENT_CONFLICT} with
 * no SQL details.
 */
public class StaffAssignmentConflictException extends RuntimeException {

	public StaffAssignmentConflictException(String message) {
		super(message);
	}
}
