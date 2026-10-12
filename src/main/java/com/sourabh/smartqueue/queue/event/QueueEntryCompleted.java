package com.sourabh.smartqueue.queue.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A serving entry completed: {@code SERVING → COMPLETED} (see ADR-016).
 *
 * <p>Lifecycle fact; the measured interaction lives in
 * {@code ServiceSessionCompleted}.
 */
public record QueueEntryCompleted(
		UUID eventId,
		Instant occurredAt,
		UUID organizationId,
		UUID branchId,
		UUID queueId,
		UUID queueEntryId,
		UUID serviceId,
		UUID customerId) {
}
