package com.sourabh.smartqueue.queue.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A called entry began service: {@code CALLED → SERVING} (see ADR-016).
 *
 * <p>Distinct from {@code ServiceSessionStarted}: this is the queue-lifecycle
 * fact (entry state), that one is the interaction record (session row).
 * Different future consumers care about each (queue displays vs analytics),
 * so both are published — not the same fact twice.
 */
public record QueueEntryServingStarted(
		UUID eventId,
		Instant occurredAt,
		UUID organizationId,
		UUID branchId,
		UUID queueId,
		UUID queueEntryId,
		UUID serviceId,
		UUID customerId,
		UUID counterId) {
}
