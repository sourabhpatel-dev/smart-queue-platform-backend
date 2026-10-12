package com.sourabh.smartqueue.queue.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.sourabh.smartqueue.queue.QueuePriority;

/**
 * A customer joined a queue and received a token (see ADR-016).
 *
 * <p>A business fact, already happened — never a command. Carries IDs and
 * small scalar snapshots only, never entities or lazy proxies, so future
 * consumers (Redis projections, WebSocket broadcasts, notifications) can
 * use it without touching persistence.
 *
 * @param eventId unique per emission (never a business-entity id — one
 * entry emits many events over its lifecycle)
 * @param occurredAt when the application recorded the fact (project Clock,
 * not the database commit instant)
 */
public record QueueEntryJoined(
		UUID eventId,
		Instant occurredAt,
		UUID organizationId,
		UUID branchId,
		UUID queueId,
		UUID queueEntryId,
		UUID serviceId,
		UUID customerId,
		LocalDate businessDate,
		int tokenNumber,
		QueuePriority priority) {
}
