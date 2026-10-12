package com.sourabh.smartqueue.branch;

/**
 * A branch name already taken in the caller's organization (see ADR-023).
 *
 * <p>Application translation of the {@code uq_branch_organization_name}
 * unique constraint: the database stays authoritative (no check-then-insert
 * race — concurrent creates serialize on the constraint), the API renders
 * {@code 409 BRANCH_NAME_CONFLICT} with no SQL details. Cross-organization
 * duplicates remain legal by design.
 */
public class BranchNameConflictException extends RuntimeException {

	public BranchNameConflictException(String message) {
		super(message);
	}
}
