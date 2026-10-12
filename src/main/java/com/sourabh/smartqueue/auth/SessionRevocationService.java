package com.sourabh.smartqueue.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password-change session revocation (see ADR-037).
 *
 * <p>Stateless HS256 tokens cannot be individually revoked, so the
 * revocation fact lives beside the secret: every
 * {@link PasswordCredentialService#setPassword} stamps
 * {@code password_changed_at}, and a token is honored only when it
 * was issued at or after that stamp. Because all password-setting
 * flows funnel through one method, none can forget revocation; a
 * successful reset or change therefore retires every previously
 * issued token immediately (not merely at its ≤1h expiry).
 *
 * <p>Precision rule, documented not hidden: JWT {@code iat} has
 * second precision while the stamp has microseconds, so the stamp
 * is truncated to seconds before comparing. A token used within the
 * same second as a reset stays valid (≤1s exposure) instead of
 * wrongly killing a login that raced the reset.
 *
 * <p>Cost, stated honestly: this check loads one credential row per
 * authenticated request (unique-key equality — no new index). Users
 * without a credential row (OAuth-only accounts) are unaffected.
 */
@Service
public class SessionRevocationService {

	private final UserCredentialRepository credentials;

	public SessionRevocationService(UserCredentialRepository credentials) {
		this.credentials = credentials;
	}

	/**
	 * @throws JwtInvalidException when the token predates the latest
	 * password change. Callers translate this into the existing
	 * generic 401 — no revocation detail reaches the client.
	 */
	@Transactional(readOnly = true)
	public void requireNotRevoked(UUID userId, Instant tokenIssuedAt) {
		Instant changedAt = credentials.findByUserId(userId)
				.map(UserCredential::getPasswordChangedAt)
				.orElse(null);
		if (changedAt != null
				&& tokenIssuedAt.isBefore(changedAt.truncatedTo(ChronoUnit.SECONDS))) {
			throw new JwtInvalidException("revoked token");
		}
	}
}
