package com.sourabh.smartqueue.queue;

/**
 * A queue operation naming a branch that does not own the queue (see
 * ADR-027).
 *
 * <p>Path branch and queue branch agree — or the combination is
 * well-formed yet invariant-violating, hence {@code 422
 * QUEUE_BRANCH_MISMATCH}, following the service/capability/appointment
 * precedent. Foreign-organization rows never reach this check
 * (scope-safe {@code 404}s first).
 */
public class QueueBranchMismatchException extends RuntimeException {

	public QueueBranchMismatchException(String message) {
		super(message);
	}
}
