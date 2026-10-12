package com.sourabh.smartqueue.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Authentication configuration (see ADR-021).
 *
 * <ul>
 *   <li>{@code secret} — HMAC signing key for access tokens. No default:
 *   it comes from the {@code JWT_SECRET} environment variable and startup
 *   fails fast when it is missing or too short for HS256 (32 bytes). Never
 *   committed, never logged, never returned to clients.</li>
 *   <li>{@code expiration} — access-token lifetime (default one hour, a
 *   short-lived token by design; no refresh tokens in this phase).</li>
 * </ul>
 *
 * <p>Static configuration: changes require a restart. Invalid values fail
 * fast at startup, in the {@code QueueAgingProperties} style.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "security.jwt")
public class AuthProperties {

	@NotBlank(message = "security.jwt.secret must be set (JWT_SECRET environment variable)")
	private String secret = "";

	@NotNull
	private Duration expiration = Duration.ofHours(1);

	public String getSecret() {
		return secret;
	}

	public void setSecret(String secret) {
		this.secret = secret;
	}

	public Duration getExpiration() {
		return expiration;
	}

	public void setExpiration(Duration expiration) {
		this.expiration = expiration;
	}

	@AssertTrue(message = "security.jwt.secret must be at least 32 bytes for HS256")
	public boolean isSecretStrongEnough() {
		return secret != null && secret.getBytes(StandardCharsets.UTF_8).length >= 32;
	}

	@AssertTrue(message = "security.jwt.expiration must be a positive duration")
	public boolean isExpirationPositive() {
		return expiration != null && !expiration.isZero() && !expiration.isNegative();
	}
}
