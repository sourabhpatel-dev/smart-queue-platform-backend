package com.sourabh.smartqueue.tenant;

/**
 * Thrown when tenant-scoped application code requires a
 * {@link TenantContext} but none is available (see ADR-019).
 *
 * <p>Fail-closed by design: a missing context is never interpreted as
 * "all tenants" or "no scope filter". The exception forces the caller to
 * establish a context explicitly or to acknowledge that the operation
 * cannot proceed safely.
 */
public class TenantContextRequiredException extends RuntimeException {

	public TenantContextRequiredException(String message) {
		super(message);
	}
}
