package com.sourabh.smartqueue.counter;

/**
 * A counter name already taken at the caller's branch (see ADR-024).
 *
 * <p>Application translation of {@code uq_counter_branch_name}: the
 * database stays authoritative (concurrent creates serialize on the
 * constraint), the API renders {@code 409 COUNTER_NAME_CONFLICT} with no
 * SQL details. Cross-branch duplicates stay legal.
 */
public class CounterNameConflictException extends RuntimeException {

	public CounterNameConflictException(String message) {
		super(message);
	}
}
