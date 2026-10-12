package com.sourabh.smartqueue.user;

/**
 * A user request the service cannot honor (see ADR-025).
 *
 * <p>Service-level input faults past Bean Validation's reach — chiefly
 * unknown role names and assignment-ineligible roles. Renders as
 * {@code 400 INVALID_USER_REQUEST}. Uniqueness conflicts and scope faults
 * have their own codes.
 */
public class InvalidUserRequestException extends RuntimeException {

	public InvalidUserRequestException(String message) {
		super(message);
	}
}
