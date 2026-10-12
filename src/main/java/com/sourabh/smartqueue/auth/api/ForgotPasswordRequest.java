package com.sourabh.smartqueue.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Password-reset request. The response is always generic (see
 * {@link GenericMessageResponse}) — unknown emails look identical
 * to known ones.
 */
public record ForgotPasswordRequest(
		@Email @NotBlank @Size(max = 255) String email) {
}
