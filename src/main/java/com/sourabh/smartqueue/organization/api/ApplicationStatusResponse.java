package com.sourabh.smartqueue.organization.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The authenticated owner's view of their own business application
 * (see ADR-041).
 *
 * <p>Owner-safe only: status, whether the owner's email verification is
 * complete, and — for rejections — the owner-visible reason plus the
 * decision time. No internal notes exist on this row (there is no
 * column for them), no other applicant's data, no persistence details.
 */
public record ApplicationStatusResponse(
		UUID organizationId,
		String organizationName,
		UUID branchId,
		String status,
		boolean emailVerified,
		String rejectionReason,
		Instant decidedAt) {
}
