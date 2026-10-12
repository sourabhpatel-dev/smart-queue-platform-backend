package com.sourabh.smartqueue.organization.api;

import java.time.Instant;
import java.util.UUID;

import com.sourabh.smartqueue.organization.Organization;

/**
 * Public organization view (see ADR-022).
 *
 * <p>API-level data only: ids as UUID strings, status as a name string,
 * timestamps as ISO-8601. No persistence details, no branch lists, no
 * credentials. Explicit manual mapping (no framework) kept next to the
 * application boundary.
 */
public record OrganizationResponse(
		UUID id,
		String name,
		String status,
		Instant createdAt,
		Instant updatedAt) {

	public static OrganizationResponse from(Organization organization) {
		return new OrganizationResponse(
				organization.getId(),
				organization.getName(),
				organization.getStatus().name(),
				organization.getCreatedAt(),
				organization.getUpdatedAt());
	}
}
