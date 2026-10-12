package com.sourabh.smartqueue.user.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.sourabh.smartqueue.user.User;

/**
 * Public user view (see ADR-025, ADR-040).
 *
 * <p>API-level data only: UUIDs, normalized email, display name, role
 * names (sorted, never ordinals), status name, ISO-8601 timestamps. Never
 * a password, hash, credential row, token, or security internals —
 * enforced by construction (no such field exists to leak). Explicit
 * manual mapping, no framework.
 *
 * <p>{@code organizationId} is {@code null} for platform-level customers,
 * who belong to no organization (ADR-040).
 */
public record UserResponse(
		UUID id,
		UUID organizationId,
		String email,
		String displayName,
		List<String> roles,
		String status,
		Instant createdAt,
		Instant updatedAt) {

	public static UserResponse from(User user) {
		return new UserResponse(
				user.getId(),
				user.getOrganization() == null ? null : user.getOrganization().getId(),
				user.getEmail(),
				user.getDisplayName(),
				user.getRoles().stream().map(Enum::name).sorted().toList(),
				user.getStatus().name(),
				user.getCreatedAt(),
				user.getUpdatedAt());
	}
}
