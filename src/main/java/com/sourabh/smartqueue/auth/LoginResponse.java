package com.sourabh.smartqueue.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A successful login (see ADR-021, ADR-040).
 *
 * <p>Carries the bearer token with its absolute expiry plus the public
 * identity it identifies: {@code organizationId} is the resolved
 * operational organization and is {@code null} for platform-level
 * customers (ADR-040). Never a password, never a hash, never a JPA
 * entity, never persistence details.
 */
public record LoginResponse(
		String tokenType,
		String accessToken,
		Instant expiresAt,
		UUID userId,
		UUID organizationId,
		List<String> roles) {

	public static LoginResponse bearer(AuthService.LoginResult result) {
		return new LoginResponse(
				"Bearer",
				result.token(),
				result.expiresAt(),
				result.userId(),
				result.organizationId(),
				result.principal().roles().stream().map(Enum::name).sorted().toList());
	}
}
