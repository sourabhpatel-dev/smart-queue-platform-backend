package com.sourabh.smartqueue.auth.api;

/**
 * Enumeration-safe envelope for forgot-password and resend flows:
 * the message is identical whether or not the account exists, so a
 * caller learns nothing about who is registered. The only honest
 * content is what to do next (check the inbox).
 */
public record GenericMessageResponse(String message) {

	public static GenericMessageResponse verificationResent() {
		return new GenericMessageResponse(
				"If an account exists for that email, a new verification link has been sent.");
	}

	public static GenericMessageResponse resetSent() {
		return new GenericMessageResponse(
				"If an account exists for that email, password reset instructions have been sent.");
	}
}
