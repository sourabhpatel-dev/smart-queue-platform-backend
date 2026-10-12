package com.sourabh.smartqueue.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Authenticated password change: prove the current secret, set a
 * fresh one. All other sessions retire immediately.
 */
public record ChangePasswordRequest(
		@NotBlank String currentPassword,
		@NotBlank @Size(max = 72) String newPassword) {
}
