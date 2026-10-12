package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sourabh.smartqueue.auth.AuthProperties;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.JwtInvalidException;
import com.sourabh.smartqueue.auth.JwtService;
import com.sourabh.smartqueue.user.RoleType;

/**
 * JWT issue/validate tests (see ADR-021): round-trip, expiry via a fixed
 * Clock (no sleeps), tampered signatures, wrong algorithms, missing claims,
 * and malformed input. Pure unit tests — no Spring context, no containers.
 */
class JwtServiceTests {

	static final Instant NOW = Instant.parse("2026-10-06T04:30:00Z");
	static final String TEST_SECRET = "jwt-service-test-only-signing-key-0123456789abcdef";

	static class ManualClock extends Clock {
		private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

		void advance(Duration duration) {
			now.updateAndGet(current -> current.plus(duration));
		}

		@Override
		public Instant instant() {
			return now.get();
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException("fixed test clock");
		}
	}

	private JwtService jwtService;
	private AuthProperties properties;
	private ManualClock clock;

	@BeforeEach
	void setUp() {
		properties = new AuthProperties();
		properties.setSecret(TEST_SECRET);
		properties.setExpiration(Duration.ofHours(1));
		clock = new ManualClock();
		jwtService = new JwtService(properties, clock);
	}

	private AuthenticatedPrincipal principal() {
		return new AuthenticatedPrincipal(UUID.randomUUID(), UUID.randomUUID(),
				"jwt@example.com", Set.of(RoleType.STAFF, RoleType.CUSTOMER));
	}

	@Test
	void issuedTokenValidatesToSamePrincipal() {
		AuthenticatedPrincipal principal = principal();

		JwtService.IssuedToken issued = jwtService.issueToken(principal);

		assertThat(issued.token()).isNotBlank();
		assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
		assertThat(jwtService.validateToken(issued.token())).isEqualTo(principal);
	}

	@Test
	void expiredTokenIsRejected() {
		String token = jwtService.issueToken(principal()).token();

		clock.advance(Duration.ofHours(1).plusSeconds(1));

		assertThatThrownBy(() -> jwtService.validateToken(token))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void tokenValidJustBeforeExpiry() {
		String token = jwtService.issueToken(principal()).token();

		clock.advance(Duration.ofHours(1).minusSeconds(1));

		assertThat(jwtService.validateToken(token)).isNotNull();
	}

	@Test
	void tokenAtExactExpiryIsRejected() {
		// RFC 7519 §4.1.4: now must be strictly before exp — the token
		// dies at its expiration instant, not one tick after it.
		String token = jwtService.issueToken(principal()).token();

		clock.advance(Duration.ofHours(1));

		assertThatThrownBy(() -> jwtService.validateToken(token))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void tamperedSignatureIsRejected() {
		String token = jwtService.issueToken(principal()).token();
		String[] parts = token.split("\\.");
		String signature = parts[2];
		// Flip the FIRST base64url char: it always carries 6 significant
		// signature bits, so the decoded bytes always change. (The last char
		// of a 32-byte signature carries 2 padding bits — flipping 'A' to 'B'
		// there can decode to identical bytes and flake. Found in Phase 3G
		// full-suite run, fixed here without touching JwtService.)
		char replacement = signature.charAt(0) == 'A' ? 'B' : 'A';
		String tampered = parts[0] + "." + parts[1] + "."
				+ replacement + signature.substring(1);

		assertThatThrownBy(() -> jwtService.validateToken(tampered))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void unsignedTokenIsRejected() {
		String unsigned = base64Url("{\"alg\":\"none\"}") + "."
				+ base64Url("{\"sub\":\"" + UUID.randomUUID() + "\"}") + ".";

		assertThatThrownBy(() -> jwtService.validateToken(unsigned))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void tokenWithoutExpiryIsRejected() throws Exception {
		AuthenticatedPrincipal principal = principal();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(principal.userId().toString())
				.claim("org", principal.organizationId().toString())
				.issueTime(java.util.Date.from(NOW))
				.build();
		SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		signed.sign(new MACSigner(properties.getSecret()));

		assertThatThrownBy(() -> jwtService.validateToken(signed.serialize()))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void customerTokenCarriesNoOrganizationClaim() {
		// ADR-040: a platform-level customer's token omits the org claim
		// entirely — and validates back to the same org-less principal.
		AuthenticatedPrincipal customer = new AuthenticatedPrincipal(
				UUID.randomUUID(), null, "customer@example.com", Set.of(RoleType.CUSTOMER));

		JwtService.IssuedToken issued = jwtService.issueToken(customer);

		assertThat(issued.token()).isNotBlank();
		AuthenticatedPrincipal back = jwtService.validateToken(issued.token());
		assertThat(back).isEqualTo(customer);
		assertThat(back.organizationId()).isNull();
		assertThat(back.hasOrganization()).isFalse();
	}

	@Test
	void tokenWithoutSubjectIsRejected() throws Exception {
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.claim("org", UUID.randomUUID().toString())
				.expirationTime(java.util.Date.from(NOW.plus(Duration.ofHours(1))))
				.build();
		SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		signed.sign(new MACSigner(properties.getSecret()));

		assertThatThrownBy(() -> jwtService.validateToken(signed.serialize()))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void tokenWithUnknownRoleIsRejected() throws Exception {
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(UUID.randomUUID().toString())
				.claim("org", UUID.randomUUID().toString())
				.claim("roles", java.util.List.of("SUPERUSER"))
				.claim("email", "x@example.com")
				.expirationTime(java.util.Date.from(NOW.plus(Duration.ofHours(1))))
				.build();
		SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		signed.sign(new MACSigner(properties.getSecret()));

		assertThatThrownBy(() -> jwtService.validateToken(signed.serialize()))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void malformedTokenIsRejected() {
		assertThatThrownBy(() -> jwtService.validateToken("not-a-token"))
				.isInstanceOf(JwtInvalidException.class);
		assertThatThrownBy(() -> jwtService.validateToken(""))
				.isInstanceOf(JwtInvalidException.class);
	}

	private static String base64Url(String json) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}
}
