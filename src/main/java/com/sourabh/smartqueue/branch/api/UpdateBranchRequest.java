package com.sourabh.smartqueue.branch.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Branch update payload (see ADR-023: PUT carries the complete mutable
 * representation).
 *
 * <p>Same mutable fields as creation, all address content plus name plus
 * zone — required every time, so partial-update semantics can never hide
 * under PUT. No {@code organizationId} (ownership is immutable — a branch
 * can never move organizations through this API), no {@code status}
 * (lifecycle moves through activate/deactivate only), no audit fields.
 */
public record UpdateBranchRequest(
		@NotBlank @Size(max = 150) String name,
		@Size(max = 255) String addressLine1,
		@Size(max = 255) String addressLine2,
		@NotBlank @Size(max = 100) String city,
		@Size(max = 100) String state,
		@Size(max = 20) String postalCode,
		@NotBlank @Size(max = 100) String country,
		@NotBlank @Size(max = 100) String timeZone) {
}
