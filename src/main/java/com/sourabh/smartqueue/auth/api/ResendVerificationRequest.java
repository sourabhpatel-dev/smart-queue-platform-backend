package com.sourabh.smartqueue.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Verification resend. The response is always the same generic
 * message regardless of whether the account exists (no
 * enumeration) — see {@link GenericMessageResponse}.
 */
public record ResendVerificationRequest(
		@Email @NotBlank @Size(max = 255) String email) {
}
