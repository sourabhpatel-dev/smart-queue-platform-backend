package com.sourabh.smartqueue.queue.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A counter claimed a waiting entry: {@code WAITING → CALLED} (see ADR-016).
 *
 * <p>Fact, not command. {@code counterId} identifies the claiming counter;
 * the entry's own {@code calledAt} is set by the same transition.
 */
public record QueueEntryCalled(
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
