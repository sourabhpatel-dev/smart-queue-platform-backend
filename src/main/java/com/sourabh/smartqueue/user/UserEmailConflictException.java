package com.sourabh.smartqueue.user;

/**
 * An email already taken by another account (see ADR-025).
 *
 * <p>Application translation of the global {@code uq_user_email} unique
 * constraint: the database stays authoritative (concurrent creates
 * serialize on the constraint — no check-then-insert race), the API
 * renders {@code 409 USER_EMAIL_CONFLICT} with no SQL details. The
 * message stays generic on purpose: it confirms only that the address is
 * taken, and only to an already-authenticated manager/admin caller.
 */
public class UserEmailConflictException extends RuntimeException {

	public UserEmailConflictException(String message) {
		super(message);
	}
}
