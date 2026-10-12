package com.sourabh.smartqueue.queue;

/**
 * No such queue in the caller's organization (see ADR-027).
 *
 * <p>Renders as scope-safe {@code 404 QUEUE_NOT_FOUND}: unknown ids and
 * foreign-organization rows are indistinguishable. The engine's own
 * {@link InvalidQueueRequestException} stays a {@code 400} for
 * genuinely invalid states (inactive branch/service/customer) — scope
 * and validity are different failures with different codes.
 */
public class QueueNotFoundException extends RuntimeException {

	public QueueNotFoundException(String message) {
		super(message);
	}
}
