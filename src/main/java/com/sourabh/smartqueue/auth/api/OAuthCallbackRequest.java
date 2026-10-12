package com.sourabh.smartqueue.auth.api;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

/**
 * Social login callback: the authorization code plus the exact
 * redirect URI it was issued for (must match the provider-side
 * registration, or the exchange fails closed). {@code organizationId}
 * is required only when the email is new — it names the organization
 * the fresh customer account joins. Existing and linked accounts
 * ignore it (their tenancy is already established).
 */
public record OAuthCallbackRequest(
		@NotBlank String code,
		@NotBlank String redirectUri,
		UUID organizationId) {
}
