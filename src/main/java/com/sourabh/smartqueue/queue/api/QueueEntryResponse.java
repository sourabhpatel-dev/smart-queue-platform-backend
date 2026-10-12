package com.sourabh.smartqueue.queue.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.sourabh.smartqueue.queue.QueueEntry;

/**
 * Public queue-entry view (see ADR-027).
 *
 * <p>API-level data only: UUIDs, token, business date, priority/status
 * names, ISO-8601 instants. {@code position} is the 1-based rank among
 * WAITING entries (same rank query as the atomic claim — what the
 * customer sees is what {@code callNext} serves), {@code 0} for
 * CALLED/SERVING (effectively now), {@code null} for terminal rows and
 * for paged record listings (positions live in the snapshot and single
 * reads, not in bulk pages — a documented cost bound). {@code
 * waitMinutes} comes from the shared estimator; {@code null} means
 * unknown (zero capable counters, inactive queue, terminal row) — never
 * a silent zero, except CALLED/SERVING which genuinely mean now.
 * The customer id rides along because every reader of this view is
 * already authorized to see it. Explicit manual mapping, no framework —
 * and no ranking/aging/wait math here: the numbers arrive computed.
 */
public record QueueEntryResponse(
		UUID id,
		UUID organizationId,
		UUID queueId,
		UUID branchId,
		UUID serviceId,
		String serviceName,
		UUID customerId,
		int tokenNumber,
		LocalDate businessDate,
		String priority,
		String status,
		Integer position,
		Integer waitMinutes,
		Instant joinedAt,
		Instant calledAt,
		Instant completedAt,
		Instant createdAt,
		Instant updatedAt) {

	public static QueueEntryResponse of(QueueEntry entry, Integer position,
			Integer waitMinutes) {
		return new QueueEntryResponse(
				entry.getId(),
				entry.getOrganization().getId(),
				entry.getQueue().getId(),
				entry.getBranch().getId(),
				entry.getQueue().getService().getId(),
				entry.getQueue().getService().getName(),
				entry.getCustomer().getId(),
				entry.getTokenNumber(),
				entry.getBusinessDate(),
				entry.getPriority().name(),
				entry.getStatus().name(),
				position,
				waitMinutes,
				entry.getJoinedAt(),
				entry.getCalledAt(),
				entry.getCompletedAt(),
				entry.getCreatedAt(),
				entry.getUpdatedAt());
	}
}
