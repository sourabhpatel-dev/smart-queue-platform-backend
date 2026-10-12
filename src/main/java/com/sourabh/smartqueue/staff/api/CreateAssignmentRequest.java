package com.sourabh.smartqueue.staff.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Assignment creation payload (see ADR-025).
 *
 * <p>One field: which branch the path user should staff. The user comes
 * from the path, the organization from the principal — both rows must
 * already live there (anything else is a scope-safe {@code 404}), and
 * the user must hold the {@code STAFF} role (assignments for other roles
 * are inert rows the policy would never consult, so they are rejected
 * instead of stored). Cross-branch user/branch pairs are rejected with
 * {@code 422}; the composite foreign keys remain the backstop.
 */
public record CreateAssignmentRequest(
		@NotNull UUID branchId) {
}
