package com.sourabh.smartqueue.queue.api;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.sourabh.smartqueue.queue.ServiceSession;

/**
 * Public service-session view: the measured interaction for one queue
 * entry (see ADR-027, session reads).
 *
 * <p>The model carries no status enum, so {@code active} is derived and
 * documented as such: {@code endedAt == null} means running (the same
 * definition the estimator and lifecycle use — no second meaning
 * invented here). {@code durationMinutes} is likewise derived
 * ({@code endedAt − startedAt}), present only once ended — never a
 * stored field, never SLA/utilization modeling. All ids a consumer needs
 * to rejoin the queue, counter, service, customer, and branch views ride
 * along; appointments are unrelated and absent. Explicit manual mapping,
 * no framework.
 */
public record ServiceSessionResponse(
		UUID id,
		UUID organizationId,
		UUID branchId,
		UUID counterId,
		UUID queueEntryId,
		UUID queueId,
		UUID serviceId,
		String serviceName,
		UUID customerId,
		boolean active,
		Instant startedAt,
		Instant endedAt,
		Integer durationMinutes,
		Instant createdAt,
		Instant updatedAt) {

	public static ServiceSessionResponse from(ServiceSession session) {
		return new ServiceSessionResponse(
				session.getId(),
				session.getBranch().getOrganization().getId(),
				session.getBranch().getId(),
				session.getCounter().getId(),
				session.getQueueEntry().getId(),
				session.getQueueEntry().getQueue().getId(),
				session.getService().getId(),
				session.getService().getName(),
				session.getQueueEntry().getCustomer().getId(),
				session.isActive(),
				session.getStartedAt(),
				session.getEndedAt(),
				session.getEndedAt() == null ? null
						: Math.toIntExact(Duration.between(
								session.getStartedAt(), session.getEndedAt()).toMinutes()),
				session.getCreatedAt(),
				session.getUpdatedAt());
	}
}
