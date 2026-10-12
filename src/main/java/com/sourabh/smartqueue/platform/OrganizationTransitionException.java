package com.sourabh.smartqueue.platform;

/**
 * A refused organization-lifecycle transition (see ADR-041).
 *
 * <p>Repeated or out-of-order decisions (approving an already-active
 * organization, rejecting a rejected one, approving an unverified
 * application) land here — never as silent no-ops and never as
 * inconsistent state. Renders as {@code 409} with a stable code so
 * concurrent reviewers get an explicit, retry-safe answer.
 */
public class OrganizationTransitionException extends RuntimeException {

	private final String code;

	public OrganizationTransitionException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String getCode() {
		return code;
	}
}
