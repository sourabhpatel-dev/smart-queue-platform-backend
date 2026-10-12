package com.sourabh.smartqueue.platform.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One organization application for platform review (see ADR-041).
 *
 * <p>Review-minimal: what a decision needs (names, owner email,
 * verification state, current status, decision audit) and nothing that
 * identifies customers, staff, or operations. The rejection reason is
 * the owner-visible text — there are no internal-only notes on this
 * row by design.
 */
public record PlatformApplicationResponse(
		UUID organizationId,
		String organizationName,
		String status,
		UUID branchId,
		String branchName,
		String ownerEmail,
		boolean emailVerified,
		String rejectionReason,
		Instant decidedAt,
		UUID decidedByUserId,
		Instant createdAt) {
}
