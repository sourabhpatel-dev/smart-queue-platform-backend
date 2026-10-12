package com.sourabh.smartqueue.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Business application: organization + initial branch + owner
 * account in one call (see ADR-041). All three commit atomically —
 * the request names everything the atomic unit needs, so no
 * follow-up call can leave an ownerless organization behind. The
 * application is created {@code PENDING_APPROVAL} with an
 * {@code INACTIVE} owner membership: nothing in this request chooses
 * a status, a role, or platform authority (unknown JSON fields are
 * ignored, never trusted). IANA timezone ids only (abbreviations
 * are rejected like everywhere else).
 */
public record RegisterBusinessRequest(
		@NotBlank @Size(max = 150) String organizationName,
		@NotBlank @Size(max = 150) String ownerName,
		@Email @NotBlank @Size(max = 255) String ownerEmail,
		@NotBlank @Size(max = 72) String password,
		@NotBlank @Size(max = 150) String branchName,
		@NotBlank @Size(max = 100) String city,
		@NotBlank @Size(max = 100) String country,
		@NotBlank @Size(max = 100) String timeZone) {
}
