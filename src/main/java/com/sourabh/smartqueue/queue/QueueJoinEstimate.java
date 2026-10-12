package com.sourabh.smartqueue.queue;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

/**
 * Point-in-time aggregate wait estimate for a new joiner who has not yet
 * entered the queue (see ADR-015). Same formula as {@link WaitTimeEstimate}
 * but position = last (new entry ranks after all current WAITING entries).
 *
 * <p>Calculated at {@code calculatedAt}; estimated wait is whole minutes from
 * that point. {@code estimatedWaitMinutes} is {@code null} when the queue is
 * inactive or has no active capable counters — the caller must treat this as
 * a snapshot, not a promise, and render an appropriate UI.
 */
public record QueueJoinEstimate(
		UUID queueId,
		int waitingCount,
		Integer estimatedWaitMinutes,
		int capableCounterCount,
		int activeSessionCount,
		Instant calculatedAt) {
}