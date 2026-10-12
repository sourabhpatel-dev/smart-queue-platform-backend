package com.sourabh.smartqueue.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Password reset by single-use token. Unknown, expired, and reused
 * tokens share one generic failure; password strength has its own
 * code ({@code WEAK_PASSWORD}).
 */
public record ResetPasswordRequest(
		@NotBlank String token,
		@NotBlank @Size(max = 72) String newPassword) {
}
