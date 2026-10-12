package com.sourabh.smartqueue.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login credentials (see ADR-021).
 *
 * <p>Passwords are capped at 72 bytes because BCrypt silently truncates
 * beyond that — accepting longer input would pretend to check what it does
 * not. The raw value lives only for the duration of the login call: never
 * persisted, never logged, never returned.
 */
public record LoginRequest(
		@Email @NotBlank @Size(max = 255) String email,
		@NotBlank @Size(max = 72) String password) {
}
