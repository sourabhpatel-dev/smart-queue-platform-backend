package com.sourabh.smartqueue.branch.api;

import java.time.Instant;
import java.util.UUID;

import com.sourabh.smartqueue.branch.Branch;

/**
 * Public branch view (see ADR-022, extended in 3H with the flat address).
 *
 * <p>API-level data only: UUID strings, status as a name string, the IANA
 * zone as its string id, timestamps as ISO-8601. No persistence details.
 * Explicit manual mapping (no framework) kept next to the application
 * boundary.
 */
public record BranchResponse(
		UUID id,
		UUID organizationId,
		String name,
		String addressLine1,
		String addressLine2,
		String city,
		String state,
		String postalCode,
		String country,
		String timeZone,
		String status,
		Instant createdAt,
		Instant updatedAt) {

	public static BranchResponse from(Branch branch) {
		return new BranchResponse(
				branch.getId(),
				branch.getOrganization().getId(),
				branch.getName(),
				branch.getAddressLine1(),
				branch.getAddressLine2(),
				branch.getCity(),
				branch.getState(),
				branch.getPostalCode(),
				branch.getCountry(),
				branch.getTimeZone().getId(),
				branch.getStatus().name(),
				branch.getCreatedAt(),
				branch.getUpdatedAt());
	}
}
