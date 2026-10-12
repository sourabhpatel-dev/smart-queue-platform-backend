package com.sourabh.smartqueue.queue.api;

import java.time.Instant;
import java.util.UUID;

import com.sourabh.smartqueue.queue.Queue;

/**
 * Public queue view: one row per branch+service (see ADR-027).
 *
 * <p>Identity plus administrative status only — live membership lives in
 * the detail view and the paged entries listing, never embedded here.
 * Queues are provisioned data with no creation endpoint, by design:
 * each service creation provisions its queue in the same transaction
 * (see ADR-038), so there is no queue-creation flow to expose.
 * Explicit manual mapping, no framework.
 */
public record QueueResponse(
		UUID id,
		UUID organizationId,
		UUID branchId,
		UUID serviceId,
		String serviceName,
		String status,
		Instant createdAt,
		Instant updatedAt) {

	public static QueueResponse from(Queue queue) {
		return new QueueResponse(
				queue.getId(),
				queue.getOrganization().getId(),
				queue.getBranch().getId(),
				queue.getService().getId(),
				queue.getService().getName(),
				queue.getStatus().name(),
				queue.getCreatedAt(),
				queue.getUpdatedAt());
	}
}
