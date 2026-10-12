package com.sourabh.smartqueue.auth;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sourabh.smartqueue.user.RoleType;

/**
 * Issues and validates short-lived HS256 access tokens (see ADR-021,
 * ADR-040).
 *
 * <p>Self-contained by design: this application is both issuer and sole
 * audience, so no external identity provider, no JWKS endpoint, no key
 * rotation ceremony. One symmetric secret (see {@link AuthProperties}),
 * one algorithm ({@code HS256} — a standard, never {@code none}), one
 * token kind (access only, no refresh tokens this phase).
 *
 * <p>Claims are minimal: {@code sub} (user id), {@code roles} (role
 * names), {@code iat}, {@code exp}, and the optional {@code org}
 * (organization id) plus {@code email}. Since ADR-040 the {@code org}
 * claim is <strong>optional</strong>: platform-level customers have no
 * organization, so their tokens omit it entirely; operational users carry
 * the organization resolved from an ACTIVE membership. No branch list
 * (assignments change independently of token lifetime — the database
 * stays authoritative), no user data, no business state. Times come from
 * the project {@link Clock}, so expiry is deterministic in tests.
 */
@Service
public class JwtService {

	static final String ORGANIZATION_CLAIM = "org";
	static final String ROLES_CLAIM = "roles";
	static final String EMAIL_CLAIM = "email";

	private final AuthProperties properties;
	private final Clock clock;

	public JwtService(AuthProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Issues a signed token for the given principal, expiring
	 * {@code security.jwt.expiration} after the project Clock's now. The
	 * {@code org} claim is written only when the principal carries a
	 * resolved operational organization (ADR-040).
	 */
	public IssuedToken issueToken(AuthenticatedPrincipal principal) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(properties.getExpiration());
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
				.subject(principal.userId().toString())
				.claim(EMAIL_CLAIM, principal.email())
				.claim(ROLES_CLAIM, principal.roles().stream().map(RoleType::name).toList())
				.issueTime(Date.from(now))
				.expirationTime(Date.from(expiresAt));
		if (principal.hasOrganization()) {
			claims.claim(ORGANIZATION_CLAIM, principal.organizationId().toString());
		}
		try {
			SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
			signed.sign(new MACSigner(properties.getSecret()));
			return new IssuedToken(signed.serialize(), expiresAt);
		} catch (JOSEException e) {
			throw new IllegalStateException("cannot sign access token", e);
		}
	}

	/**
	 * Validates structure, signature, expiry, and required claims.
	 *
	 * @throws JwtInvalidException for anything untrustworthy.
	 */
	public AuthenticatedPrincipal validateToken(String token) {
		return inspect(token).principal();
	}

	/**
	 * Validates like {@link #validateToken(String)} and additionally
	 * reports when the token was issued (its {@code iat}, second
	 * precision). The revocation check needs the issue instant to
	 * compare against password changes (see ADR-037).
	 *
	 * @throws JwtInvalidException for anything untrustworthy.
	 */
	public Inspection inspect(String token) {
		SignedJWT signed;
		try {
			signed = SignedJWT.parse(token);
		} catch (ParseException e) {
			throw new JwtInvalidException("malformed token", e);
		}
		if (!JWSAlgorithm.HS256.equals(signed.getHeader().getAlgorithm())) {
			throw new JwtInvalidException("unexpected token algorithm");
		}
		try {
			if (!signed.verify(new MACVerifier(properties.getSecret()))) {
				throw new JwtInvalidException("invalid token signature");
			}
		} catch (JOSEException e) {
			throw new JwtInvalidException("cannot verify token signature", e);
		}
		JWTClaimsSet claims;
		try {
			claims = signed.getJWTClaimsSet();
		} catch (ParseException e) {
			throw new JwtInvalidException("unreadable token claims", e);
		}
		Date expiration = claims.getExpirationTime();
		// RFC 7519 §4.1.4: the current date/time MUST be before exp —
		// at the exact expiration instant the token is already expired.
		if (expiration == null || !expiration.toInstant().isAfter(clock.instant())) {
			throw new JwtInvalidException("expired token");
		}
		String subject = claims.getSubject();
		String organization = readStringClaim(claims, ORGANIZATION_CLAIM);
		if (subject == null) {
			throw new JwtInvalidException("missing required token claims");
		}
		UUID userId;
		UUID organizationId = null;
		try {
			userId = UUID.fromString(subject);
			// The org claim is optional since ADR-040: platform-level
			// customers carry no organization at all.
			if (organization != null) {
				organizationId = UUID.fromString(organization);
			}
		} catch (IllegalArgumentException e) {
			throw new JwtInvalidException("invalid token identity claims", e);
		}
		String email = readStringClaim(claims, EMAIL_CLAIM);
		Date issuedAt = claims.getIssueTime();
		if (issuedAt == null) {
			throw new JwtInvalidException("missing token issue time");
		}
		return new Inspection(
				new AuthenticatedPrincipal(userId, organizationId, email, readRoles(claims)),
				issuedAt.toInstant());
	}

	private static String readStringClaim(JWTClaimsSet claims, String name) {
		try {
			return claims.getStringClaim(name);
		} catch (ParseException e) {
			throw new JwtInvalidException("unreadable token claims", e);
		}
	}

	private static Set<RoleType> readRoles(JWTClaimsSet claims) {
		List<String> names;
		try {
			names = claims.getStringListClaim(ROLES_CLAIM);
		} catch (ParseException e) {
			throw new JwtInvalidException("unreadable token claims", e);
		}
		if (names == null) {
			return Set.of();
		}
		Set<RoleType> roles = EnumSet.noneOf(RoleType.class);
		for (String name : names) {
			try {
				roles.add(RoleType.valueOf(name));
			} catch (IllegalArgumentException e) {
				throw new JwtInvalidException("unknown role in token", e);
			}
		}
		return roles;
	}

	/** A signed token with its absolute expiry. */
	public record IssuedToken(String token, Instant expiresAt) {
	}

	/** A validated principal plus the token's issue instant. */
	public record Inspection(AuthenticatedPrincipal principal, Instant issuedAt) {
	}
}
