package com.sourabh.smartqueue.queue.event;

import java.time.Instant;
import java.util.UUID;

/**
 * An actual service interaction began for a queue entry (see ADR-016).
 *
 * <p>Distinct from {@code QueueEntryServingStarted}: this is the
 * interaction record (session row with counter and start instant), that one
 * is the queue-lifecycle transition. Published together because the
 * lifecycle service creates both atomically — consumers subscribe to
 * whichever fact they need.
 */
public record ServiceSessionStarted(
		UUID eventId,
		Instant occurredAt,
		UUID organizationId,
		UUID branchId,
		UUID queueEntryId,
		UUID serviceSessionId,
		UUID serviceCounterId,
		UUID serviceId,
		UUID customerId,
		Instant startedAt) {
}
