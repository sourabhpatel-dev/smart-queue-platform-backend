package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.SignedJWT;
import com.sourabh.smartqueue.auth.oauth.AppleClientSecretFactory;
import com.sourabh.smartqueue.auth.oauth.OAuthProperties;

/**
 * Apple client-secret minting (see the OAuth decision notes): a
 * well-formed ES256 JWT with the configured team/key/subject and a
 * five-minute life, verifiable against the matching public key —
 * using JDK-generated keys, no Apple account. Proves the exact
 * bytes the token endpoint would receive.
 */
class AppleClientSecretFactoryTests {

	private OAuthProperties properties(String privateKeyPem) {
		OAuthProperties properties = new OAuthProperties();
		properties.getApple().setClientId("com.example.service");
		properties.getApple().setTeamId("TEAM123456");
		properties.getApple().setKeyId("KEY1234567");
		properties.getApple().setPrivateKey(privateKeyPem);
		return properties;
	}

	private String pem(java.security.PrivateKey key) {
		String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'})
				.encodeToString(key.getEncoded());
		return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
	}

	@Test
	void mintedSecretVerifiesAgainstMatchingPublicKey() throws Exception {
		Instant now = Instant.parse("2026-10-08T00:00:00Z");
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		KeyPair pair = generator.generateKeyPair();
		AppleClientSecretFactory factory = new AppleClientSecretFactory(
				properties(pem(pair.getPrivate())), Clock.fixed(now, ZoneOffset.UTC));

		String secret = factory.mint();

		SignedJWT parsed = SignedJWT.parse(secret);
		assertThat(parsed.getHeader().getAlgorithm().getName()).isEqualTo("ES256");
		assertThat(parsed.getHeader().getKeyID()).isEqualTo("KEY1234567");
		assertThat(parsed.getJWTClaimsSet().getIssuer()).isEqualTo("TEAM123456");
		assertThat(parsed.getJWTClaimsSet().getSubject()).isEqualTo("com.example.service");
		assertThat(parsed.getJWTClaimsSet().getExpirationTime().toInstant())
				.isEqualTo(now.plusSeconds(300));
		ECKey publicKey = new ECKey.Builder(Curve.P_256,
				(java.security.interfaces.ECPublicKey) pair.getPublic()).build();
		JWSVerifier verifier = new ECDSAVerifier(publicKey);
		assertThat(parsed.verify(verifier)).isTrue();
	}

	@Test
	void garbageKeyMaterialFailsFast() {
		AppleClientSecretFactory factory = new AppleClientSecretFactory(
				properties("not-a-key"), Clock.systemUTC());

		assertThatThrownBy(factory::mint).isInstanceOf(IllegalStateException.class);
	}
}
