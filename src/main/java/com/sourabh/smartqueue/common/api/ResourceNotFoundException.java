package com.sourabh.smartqueue.common.api;

/**
 * Scope-safe "no such resource for this caller" (see ADR-022).
 *
 * <p>Thrown when an id is unknown <em>or</em> belongs to another organization:
 * both render as {@code 404} with the same code family, so resource ids can
 * never be used to enumerate other tenants. Insufficient role access within
 * the caller's own organization is a separate {@code 403} (see
 * {@link org.springframework.security.access.AccessDeniedException}).
 */
public class ResourceNotFoundException extends RuntimeException {

	private final String code;

	public ResourceNotFoundException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String getCode() {
		return code;
	}
}
