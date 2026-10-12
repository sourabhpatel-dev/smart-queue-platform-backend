package com.sourabh.smartqueue.staff;

/**
 * No such assignment in the caller's organization (see ADR-025).
 *
 * <p>Renders as scope-safe {@code 404 STAFF_ASSIGNMENT_NOT_FOUND}:
 * unknown ids and foreign-organization rows are indistinguishable.
 */
public class StaffAssignmentNotFoundException extends RuntimeException {

	public StaffAssignmentNotFoundException(String message) {
		super(message);
	}
}
