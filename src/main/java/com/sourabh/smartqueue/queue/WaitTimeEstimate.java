package com.sourabh.smartqueue.queue;

import java.time.Instant;
import java.util.UUID;

/**
 * A point-in-time waiting estimate for one queue entry (see ADR-015).
 *
 * <p>Derived, approximate, and honest about it: whole minutes from the
 * entry's position in service order, never seconds of false precision.
 * May change as queue state changes — callers must treat it as a snapshot,
 * not a promise.
 *
 * @param entryId the estimated entry
 * @param waitMinutes whole minutes from {@code calculatedAt} until expected
 * service start (0 means effectively now)
 * @param estimatedStart {@code calculatedAt} plus {@code waitMinutes}
 * @param calculatedAt when the estimate was computed (project Clock)
 * @param entriesAhead waiting + called work ranked before the entry
 * @param activeSessions open sessions occupying capable counters
 * @param servingCounters active counters capable of the entry's service
 */
public record WaitTimeEstimate(
		UUID entryId,
		int waitMinutes,
		Instant estimatedStart,
		Instant calculatedAt,
		int entriesAhead,
		int activeSessions,
		int servingCounters) {
}
