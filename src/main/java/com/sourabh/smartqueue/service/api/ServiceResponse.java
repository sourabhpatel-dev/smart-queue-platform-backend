package com.sourabh.smartqueue.service.api;

import java.time.Instant;
import java.util.UUID;

import com.sourabh.smartqueue.service.Service;

/**
 * Public service view (see ADR-024).
 *
 * <p>API-level data only: UUIDs, duration as whole minutes (never a
 * framework-specific duration structure), status as a name string,
 * timestamps as ISO-8601. Explicit manual mapping, no framework.
 */
public record ServiceResponse(
		UUID id,
		UUID organizationId,
		UUID branchId,
		String name,
		String description,
		int durationMinutes,
		String status,
		Instant createdAt,
		Instant updatedAt) {

	public static ServiceResponse from(Service service) {
		return new ServiceResponse(
				service.getId(),
				service.getBranch().getOrganization().getId(),
				service.getBranch().getId(),
				service.getName(),
				service.getDescription(),
				Math.toIntExact(service.getStandardDuration().toMinutes()),
				service.getStatus().name(),
				service.getCreatedAt(),
				service.getUpdatedAt());
	}
}
