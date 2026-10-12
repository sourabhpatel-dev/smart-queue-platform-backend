package com.sourabh.smartqueue.counter;

/**
 * A capability linking rows of different branches was requested (see
 * ADR-024).
 *
 * <p>A counter must never serve another branch's service, even inside the
 * same organization. The application rejects this before the database
 * with {@code 422 CAPABILITY_BRANCH_MISMATCH}; the V3 composite foreign
 * keys remain the backstop no code path can bypass. Cross-organization
 * rows never reach this check — they are scope-safe {@code 404}s first.
 */
public class CapabilityBranchMismatchException extends RuntimeException {

	public CapabilityBranchMismatchException(String message) {
		super(message);
	}
}
