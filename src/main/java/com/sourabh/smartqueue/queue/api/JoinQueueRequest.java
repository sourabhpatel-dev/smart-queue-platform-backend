package com.sourabh.smartqueue.queue.api;

/**
 * Walk-in join payload (see ADR-027).
 *
 * <p>Priority is optional and defaults to {@code NORMAL}. Requesting
 * {@code PRIORITY} or {@code EMERGENCY} requires operational authority
 * at the branch (triage is a staff function — customers never
 * self-declare urgency, and unassigned staff cannot triage elsewhere);
 * anything else yields {@code 403}. Unknown names yield {@code 400}.
 * The customer is always the principal, the token/date/position are
 * always server-derived — the body carries nothing else.
 */
public record JoinQueueRequest(
		String priority) {
}
