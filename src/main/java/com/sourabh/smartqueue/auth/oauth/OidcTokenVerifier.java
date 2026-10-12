package com.sourabh.smartqueue.auth.oauth;

import java.net.URL;
import java.text.ParseException;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.sourabh.smartqueue.auth.JwtInvalidException;

/**
 * Standards-based OIDC ID-token validation on the existing Nimbus
 * library (no new dependencies): signature against the provider's
 * JWKS (cached RemoteJWKSet), then issuer, audience, and expiry.
 * Anything untrustworthy raises {@link JwtInvalidException} — the
 * same generic failure family as local tokens. Frontend-supplied
 * identity data is never trusted; only this verification mints
 * local sessions.
 */
@Component
public class OidcTokenVerifier {

	static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";

	static final String GOOGLE_ISSUER = "https://accounts.google.com";

	static final String APPLE_JWKS = "https://appleid.apple.com/auth/keys";

	static final String APPLE_ISSUER = "https://appleid.apple.com";

	private final Clock clock;
	private final Map<String, JWKSource<SecurityContext>> jwksCache = new ConcurrentHashMap<>();

	@org.springframework.beans.factory.annotation.Autowired
	public OidcTokenVerifier(Clock clock) {
		this.clock = clock;
	}

	/**
	 * Test seam: preset key sources (e.g. in-memory sets) instead of
	 * remote JWKS endpoints, so validation logic is proven without
	 * network. Production always uses the single-argument
	 * constructor (remote endpoints above).
	 */
	public OidcTokenVerifier(Clock clock, Map<String, JWKSource<SecurityContext>> preset) {
		this.clock = clock;
		this.jwksCache.putAll(preset);
	}

	/**
	 * Both providers publish RSA keys for ID tokens (ES256 appears
	 * only in Apple's client-secret minting, never in their ID
	 * tokens), so RS256 is the only accepted algorithm — never
	 * {@code none}, never a caller-supplied list.
	 */
	public VerifiedIdentity verify(String idToken, String expectedIssuer,
			String expectedAudience, String jwksUrl) {
		SignedJWT signed;
		try {
			signed = SignedJWT.parse(idToken);
		} catch (ParseException e) {
			throw new JwtInvalidException("malformed identity token", e);
		}
		try {
			ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
			JWKSource<SecurityContext> jwks = jwksCache.computeIfAbsent(jwksUrl, url -> {
				try {
					return new RemoteJWKSet<>(new URL(url));
				} catch (Exception e) {
					throw new JwtInvalidException("identity provider unreachable", e);
				}
			});
			JWSKeySelector<SecurityContext> selector =
					new JWSVerificationKeySelector<SecurityContext>(
							java.util.Set.of(JWSAlgorithm.RS256), jwks);
			processor.setJWSKeySelector(selector);
			// No-op claims verifier: the default one checks expiry
			// against the system clock, but every check below runs on
			// the project Clock (deterministic in tests, and the exact
			// semantics — strict after-expiry — stay in one place).
			processor.setJWTClaimsSetVerifier((claims, context) -> {
			});
			JWTClaimsSet claims = processor.process(signed, null);
			if (!expectedIssuer.equals(claims.getIssuer())) {
				throw new JwtInvalidException("unexpected identity issuer");
			}
			List<String> audiences = claims.getAudience();
			if (audiences == null || !audiences.contains(expectedAudience)) {
				throw new JwtInvalidException("unexpected identity audience");
			}
			Date expiry = claims.getExpirationTime();
			if (expiry == null || !expiry.toInstant().isAfter(clock.instant())) {
				throw new JwtInvalidException("expired identity token");
			}
			String subject = claims.getSubject();
			String email;
			try {
				email = claims.getStringClaim("email");
			} catch (ParseException e) {
				throw new JwtInvalidException("unreadable identity claims", e);
			}
			if (subject == null || subject.isBlank() || email == null || email.isBlank()) {
				throw new JwtInvalidException("incomplete identity token");
			}
			return new VerifiedIdentity(subject, email,
					Boolean.TRUE.equals(claims.getBooleanClaim("email_verified")));
		} catch (JwtInvalidException e) {
			throw e;
		} catch (BadJOSEException | java.text.ParseException e) {
			throw new JwtInvalidException("untrusted identity token", e);
		} catch (Exception e) {
			throw new JwtInvalidException("identity verification failed", e);
		}
	}
}
