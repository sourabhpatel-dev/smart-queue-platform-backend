package com.sourabh.smartqueue.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

/**
 * Single-use account lifecycle secrets (see ADR-037).
 *
 * <p>256-bit {@link SecureRandom} values, URL-safe base64 without
 * padding (safe in query strings, no escaping ceremony). Only the
 * SHA-256 hex digest is ever persisted — the raw token exists
 * transiently at creation and travels exactly once inside the email
 * body. The digest exists only for equality matching (no stretching
 * needed: 256 bits of entropy are not brute-forced); never logged,
 * never returned.
 */
@Component
public class AccountTokenGenerator {

	private final SecureRandom random = new SecureRandom();

	/** A fresh raw token for exactly one out-of-band delivery. */
	public String generate() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** The stored form of a raw token (unique column, reuse proof). */
	public String digest(String rawToken) {
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			byte[] hash = sha256.digest(rawToken.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
