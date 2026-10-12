package com.sourabh.smartqueue.auth;

import com.sourabh.smartqueue.common.api.InvalidRequestException;

/**
 * The single password rule (see ADR-037): length within
 * [{@code password-min-length}, {@code password-max-length}] (the
 * ceiling is the BCrypt 72-byte limit — longer inputs would truncate
 * silently, so they are rejected instead), with at least one letter
 * and one digit. Every password-setting flow (registration,
 * invitation acceptance, reset, change, OAuth provisioning) funnels
 * through here — no second policy anywhere.
 */
public final class PasswordPolicy {

	private PasswordPolicy() {
	}

	public static void requireValid(String rawPassword, AccountLifecycleProperties policy) {
		if (rawPassword == null || rawPassword.length() < policy.getPasswordMinLength()
				|| rawPassword.length() > policy.getPasswordMaxLength()) {
			throw new InvalidRequestException("WEAK_PASSWORD",
					"Password must be between " + policy.getPasswordMinLength()
							+ " and " + policy.getPasswordMaxLength() + " characters");
		}
		boolean letter = false;
		boolean digit = false;
		for (int i = 0; i < rawPassword.length(); i++) {
			char c = rawPassword.charAt(i);
			if (Character.isLetter(c)) {
				letter = true;
			} else if (Character.isDigit(c)) {
				digit = true;
			}
		}
		if (!letter || !digit) {
			throw new InvalidRequestException("WEAK_PASSWORD",
					"Password must contain at least one letter and one digit");
		}
	}
}
