package com.sourabh.smartqueue.auth;

/**
 * Thrown when a presented JWT cannot be trusted (see ADR-021): malformed
 * content, bad signature, expired token, or missing required claims.
 *
 * <p>Messages may be specific for logs and tests — the HTTP and STOMP
 * boundaries always translate this to a generic unauthenticated response,
 * never the detail.
 */
public class JwtInvalidException extends RuntimeException {

	public JwtInvalidException(String message) {
		super(message);
	}

	public JwtInvalidException(String message, Throwable cause) {
		super(message, cause);
	}
}
