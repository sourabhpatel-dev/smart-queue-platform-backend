package com.sourabh.smartqueue.auth.api;

import java.util.UUID;

/**
 * Public registration outcome (see ADR-040).
 *
 * <p>{@code organizationId} is {@code null} for customer registration — a
 * platform-level identity belongs to no organization — and populated only
 * by business registration, which creates the organization. {@code branchId}
 * is always {@code null} for customers.
 */
public record RegistrationResponse(
		UUID userId,
		UUID organizationId,
		UUID branchId,
		String email,
		boolean verificationRequired) {
}
