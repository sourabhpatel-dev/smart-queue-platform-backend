package com.sourabh.smartqueue.auth.oauth;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import org.springframework.stereotype.Component;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Apple client secrets (Apple's documented ES256 scheme): a
 * short-lived JWT minted per exchange from the configured team/key
 * material. Five-minute lifetime, fresh on every call — minted,
 * used once in the token form, never stored, never logged.
 */
@Component
public class AppleClientSecretFactory {

	static final String AUDIENCE = "https://appleid.apple.com";

	private final OAuthProperties properties;
	private final Clock clock;

	public AppleClientSecretFactory(OAuthProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	/** Package-visible test seam alongside {@link OAuthService#loginWithIdentity}. */
	public 	String mint() {
		OAuthProperties.Apple apple = properties.getApple();
		try {
			PrivateKey key = privateKey(apple.getPrivateKey());
			Instant now = clock.instant();
			JWTClaimsSet claims = new JWTClaimsSet.Builder()
					.issuer(apple.getTeamId())
					.subject(apple.getClientId())
					.audience(AUDIENCE)
					.issueTime(Date.from(now))
					.expirationTime(Date.from(now.plusSeconds(300)))
					.build();
			SignedJWT jwt = new SignedJWT(
					new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(apple.getKeyId()).build(),
					claims);
			jwt.sign(new ECDSASigner(key, Curve.P_256));
			return jwt.serialize();
		} catch (Exception e) {
			throw new IllegalStateException("cannot mint Apple client secret", e);
		}
	}

	/**
	 * PKCS#8 PEM (the .p8 format Apple issues) to a private key with
	 * plain JDK crypto — no PEM library, no key ever persisted. Any
	 * malformed material fails here, before any network call.
	 */
	private PrivateKey privateKey(String pem) throws Exception {
		String armored = pem.replace("-----BEGIN PRIVATE KEY-----", "")
				.replace("-----END PRIVATE KEY-----", "")
				.replaceAll("\\s", "");
		byte[] der = Base64.getDecoder().decode(armored);
		return KeyFactory.getInstance("EC")
				.generatePrivate(new PKCS8EncodedKeySpec(der));
	}
}
