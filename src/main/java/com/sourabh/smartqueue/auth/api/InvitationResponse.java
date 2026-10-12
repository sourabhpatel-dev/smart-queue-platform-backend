package com.sourabh.smartqueue.auth.api;

import java.time.Instant;
import java.util.UUID;

import com.sourabh.smartqueue.auth.OrganizationInvitation;

/**
 * One invitation as management sees it: who, what role, where, and
 * whether it is still live — never the token (it travels by email
 * only). {@code status} is derived honestly from the row
 * ({@code PENDING}, {@code ACCEPTED}, {@code REVOKED},
 * {@code EXPIRED}), evaluated against the response instant.
 */
public record InvitationResponse(
		UUID id,
		String email,
		String role,
		UUID branchId,
		String branchName,
		String status,
		Instant expiresAt,
		Instant createdAt) {

	public static InvitationResponse from(OrganizationInvitation invitation, Instant now) {
		String status;
		if (invitation.getAcceptedAt() != null) {
			status = "ACCEPTED";
		} else if (invitation.getRevokedAt() != null) {
			status = "REVOKED";
		} else if (!invitation.getExpiresAt().isAfter(now)) {
			status = "EXPIRED";
		} else {
			status = "PENDING";
		}
		return new InvitationResponse(
				invitation.getId(),
				invitation.getEmail(),
				invitation.getRole().name(),
				invitation.getBranch() == null ? null : invitation.getBranch().getId(),
				invitation.getBranch() == null ? null : invitation.getBranch().getName(),
				status,
				invitation.getExpiresAt(),
				invitation.getCreatedAt());
	}
}
