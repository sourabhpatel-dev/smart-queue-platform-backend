package com.sourabh.smartqueue.user.api;

import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * User creation payload (see ADR-025).
 *
 * <p>Client-controlled fields only: no {@code id}, no
 * {@code organizationId} (derived from the principal), no
 * {@code status} (born {@code ACTIVE}), no audit fields — and crucially
 * no password hash or credential record. An optional plaintext
 * {@code password} may provision the initial credential: it is BCrypt-
 * hashed immediately by the existing credential service and never stored
 * or returned. Omitting it creates a credential-less user that fails
 * login generically until a password flow (deferred) provisions one.
 *
 * <p>Bounds mirror the columns; the 72-char password cap is the BCrypt
 * truncation rule inherited from the login contract, and the 8-char
 * minimum is the documented floor for provisioned passwords.
 */
public record CreateUserRequest(
		@Email @NotBlank @Size(max = 255) String email,
		@NotBlank @Size(max = 150) String displayName,
		List<String> roles,
		@Size(min = 8, max = 72) String password) {
}
