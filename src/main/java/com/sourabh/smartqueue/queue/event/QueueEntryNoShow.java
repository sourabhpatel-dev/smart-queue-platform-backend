package com.sourabh.smartqueue.queue.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A waiting or called entry was marked no-show (see ADR-016).
 */
public record QueueEntryNoShow(
		UUID eventId,
		Instant occurredAt,
		UUID organizationId,
		UUID branchId,
		UUID queueId,
		UUID queueEntryId,
		UUID serviceId,
		UUID customerId) {
}
