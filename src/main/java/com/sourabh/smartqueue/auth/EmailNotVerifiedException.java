package com.sourabh.smartqueue.auth;

/**
 * Login refused while an unconsumed verification token exists for
 * the account (see ADR-037). Renders as {@code 403} with a stable
 * code (not the generic {@code FORBIDDEN}) so the frontend can
 * offer the resend flow directly. Raised only after the password
 * verifies — verification status is never probeable without the
 * credential.
 */
public class EmailNotVerifiedException extends RuntimeException {

	public EmailNotVerifiedException(String message) {
		super(message);
	}
}
