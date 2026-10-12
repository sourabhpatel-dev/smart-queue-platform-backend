package com.sourabh.smartqueue.queue.api;

import java.time.Instant;
import java.util.List;

/**
 * Operational queue snapshot for REST (see ADR-027).
 *
 * <p>The same PostgreSQL-derived state the realtime projection serves,
 * with REST-grade identity: live entries (WAITING in claim rank order,
 * then CALLED/SERVING) carry their ids, customers, positions, and
 * waits; terminal history stays in the paged entries listing. Ordering
 * and waits are identical to the realtime view by construction (same
 * rank query, same estimator) — the views differ only in identity
 * payload, because realtime is anonymous by design and REST is
 * authorized. Rebuilt on every read; never served from Redis.
 */
public record QueueDetailResponse(
		QueueResponse queue,
		int waitingCount,
		int calledCount,
		int servingCount,
		List<QueueEntryResponse> entries,
		Instant generatedAt) {
}
