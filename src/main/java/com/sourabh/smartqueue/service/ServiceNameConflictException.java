package com.sourabh.smartqueue.service;

/**
 * A service name already taken at the caller's branch (see ADR-024).
 *
 * <p>Application translation of {@code uq_service_branch_name}: the
 * database stays authoritative (concurrent creates serialize on the
 * constraint), the API renders {@code 409 SERVICE_NAME_CONFLICT} with no
 * SQL details. Cross-branch duplicates stay legal.
 */
public class ServiceNameConflictException extends RuntimeException {

	public ServiceNameConflictException(String message) {
		super(message);
	}
}
