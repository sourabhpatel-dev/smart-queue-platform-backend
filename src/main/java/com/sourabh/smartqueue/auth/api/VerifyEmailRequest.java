package com.sourabh.smartqueue.auth.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Email verification by single-use token. Unknown, expired, and
 * reused tokens share one generic failure — the token space is
 * unguessable and no signal is owed.
 */
public record VerifyEmailRequest(@NotBlank String token) {
}
