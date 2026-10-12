package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sourabh.smartqueue.auth.JwtInvalidException;
import com.sourabh.smartqueue.auth.oauth.OidcTokenVerifier;
import com.sourabh.smartqueue.auth.oauth.VerifiedIdentity;

/**
 * OIDC validation rules (see the OAuth decision notes): issuer,
 * audience, signature, expiry, and completeness — all against
 * self-signed keys and a manual Clock, no network, no provider.
 */
class OidcTokenVerifierTests {

	static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
	static final String ISSUER = "https://accounts.example.test";
	static final String AUDIENCE = "test-client-id";
	static final String JWKS = "https://issuer.example.test/certs";

	static class ManualClock extends Clock {
		final AtomicReference<Instant> now = new AtomicReference<>(NOW);

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException("test clock");
		}

		@Override
		public Instant instant() {
			return now.get();
		}
	}

	private ManualClock clock;
	private RSAKey signingKey;
	private RSAKey otherKey;
	private OidcTokenVerifier verifier;

	@BeforeEach
	void setUp() throws Exception {
		clock = new ManualClock();
		signingKey = new RSAKeyGenerator(2048).keyID("signing").generate();
		otherKey = new RSAKeyGenerator(2048).keyID("other").generate();
		verifier = new OidcTokenVerifier(clock, Map.of(JWKS,
				new ImmutableJWKSet<SecurityContext>(
						new com.nimbusds.jose.jwk.JWKSet(signingKey.toPublicJWK()))));
	}

	private String token(RSAKey key, String issuer, String audience, Instant expiry,
			String subject, String email, Boolean verified) throws Exception {
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
				.issuer(issuer)
				.audience(audience)
				.subject(subject)
				.expirationTime(Date.from(expiry))
				.issueTime(Date.from(NOW))
				.claim("email", email);
		if (verified != null) {
			claims.claim("email_verified", verified);
		}
		SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
				claims.build());
		jwt.sign(new RSASSASigner(key));
		return jwt.serialize();
	}

	private String validToken() throws Exception {
		return token(signingKey, ISSUER, AUDIENCE, NOW.plus(Duration.ofMinutes(5)),
				"sub-123", "user@example.com", true);
	}

	@Test
	void validTokenVerifies() throws Exception {
		VerifiedIdentity identity = verifier.verify(validToken(), ISSUER, AUDIENCE, JWKS);

		assertThat(identity.subject()).isEqualTo("sub-123");
		assertThat(identity.email()).isEqualTo("user@example.com");
		assertThat(identity.emailVerified()).isTrue();
	}

	@Test
	void wrongIssuerIsRejected() throws Exception {
		String forged = token(signingKey, "https://evil.example.test", AUDIENCE,
				NOW.plus(Duration.ofMinutes(5)), "sub-123", "user@example.com", true);

		assertThatThrownBy(() -> verifier.verify(forged, ISSUER, AUDIENCE, JWKS))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void wrongAudienceIsRejected() throws Exception {
		String forged = token(signingKey, ISSUER, "other-client", NOW.plus(Duration.ofMinutes(5)),
				"sub-123", "user@example.com", true);

		assertThatThrownBy(() -> verifier.verify(forged, ISSUER, AUDIENCE, JWKS))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void wrongSignatureIsRejected() throws Exception {
		String forged = token(otherKey, ISSUER, AUDIENCE, NOW.plus(Duration.ofMinutes(5)),
				"sub-123", "user@example.com", true);

		assertThatThrownBy(() -> verifier.verify(forged, ISSUER, AUDIENCE, JWKS))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void expiredTokenIsRejected() throws Exception {
		String stale = token(signingKey, ISSUER, AUDIENCE, NOW.minus(Duration.ofMinutes(1)),
				"sub-123", "user@example.com", true);

		assertThatThrownBy(() -> verifier.verify(stale, ISSUER, AUDIENCE, JWKS))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void missingEmailIsRejected() throws Exception {
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer(ISSUER)
				.audience(AUDIENCE)
				.subject("sub-123")
				.expirationTime(Date.from(NOW.plus(Duration.ofMinutes(5))))
				.build();
		SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
				claims);
		jwt.sign(new RSASSASigner(signingKey));

		assertThatThrownBy(() -> verifier.verify(jwt.serialize(), ISSUER, AUDIENCE, JWKS))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void unverifiedEmailFlagSurvives() throws Exception {
		String unverified = token(signingKey, ISSUER, AUDIENCE, NOW.plus(Duration.ofMinutes(5)),
				"sub-123", "user@example.com", false);

		VerifiedIdentity identity = verifier.verify(unverified, ISSUER, AUDIENCE, JWKS);

		assertThat(identity.emailVerified()).isFalse();
	}

	@Test
	void garbageIsRejected() {
		assertThatThrownBy(() -> verifier.verify("not-a-jwt", ISSUER, AUDIENCE, JWKS))
				.isInstanceOf(JwtInvalidException.class);
	}
}
