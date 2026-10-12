package com.sourabh.smartqueue.auth.oauth;

/**
 * A provider identity after full ID-token validation (issuer,
 * audience, signature, expiry). The email is provider-asserted
 * (Google verifies it; Apple may supply a private relay address —
 * either way the {@code sub} is the stable link key and the email
 * feeds the documented account-linking policy, never a second
 * lookup by itself).
 */
public record VerifiedIdentity(String subject, String email, boolean emailVerified) {
}
