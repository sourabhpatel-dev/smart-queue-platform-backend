package com.sourabh.smartqueue.queue.event;

import java.time.Instant;
import java.util.UUID;

/**
 * An actual service interaction ended for a queue entry (see ADR-016).
 *
 * <p>Carries both instants so future duration analytics never recompute
 * them from queue state; the lifecycle fact travels in
 * {@code QueueEntryCompleted}.
 */
public record ServiceSessionCompleted(
		UUID eventId,
		Instant occurredAt,
		UUID organizationId,
		UUID branchId,
		UUID queueEntryId,
		UUID serviceSessionId,
		UUID serviceCounterId,
		UUID serviceId,
		UUID customerId,
		Instant startedAt,
		Instant endedAt) {
}
