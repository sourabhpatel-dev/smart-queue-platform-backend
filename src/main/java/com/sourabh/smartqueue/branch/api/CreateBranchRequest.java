package com.sourabh.smartqueue.branch.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Branch creation payload (see ADR-023).
 *
 * <p>Client-controlled branch information only: no {@code id} (server
 * assigns), no {@code organizationId} (derived from the authenticated
 * principal — any supplied value is ignored, never trusted), no
 * {@code status} (branches are born {@code ACTIVE}; lifecycle moves
 * through the explicit activate/deactivate commands), no audit fields.
 * Bounds mirror the entity columns so malformed input fails at the
 * boundary; the IANA zone string is parsed by the service.
 */
public record CreateBranchRequest(
		@NotBlank @Size(max = 150) String name,
		@Size(max = 255) String addressLine1,
		@Size(max = 255) String addressLine2,
		@NotBlank @Size(max = 100) String city,
		@Size(max = 100) String state,
		@Size(max = 20) String postalCode,
		@NotBlank @Size(max = 100) String country,
		@NotBlank @Size(max = 100) String timeZone) {
}
