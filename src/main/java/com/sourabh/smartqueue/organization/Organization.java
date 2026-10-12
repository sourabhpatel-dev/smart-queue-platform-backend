package com.sourabh.smartqueue.organization;

import java.time.Instant;
import java.util.UUID;

import com.sourabh.smartqueue.common.BaseAuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A tenant/customer using the platform (see ADR-007, ADR-041).
 *
 * <p>Top of the tenancy hierarchy: every Branch belongs to exactly one
 * Organization, so tenant isolation can always be resolved as
 * {@code Branch → Organization}.
 *
 * <p>Names are intentionally NOT globally unique: two tenants may share a
 * display name (e.g. "City Clinic"). Uniqueness comes from the UUID
 * identifier (see ADR-006).
 *
 * <p><strong>Onboarding lifecycle (Phase 4R.4):</strong> business
 * applications are created {@code PENDING_APPROVAL} and become
 * {@code ACTIVE} only through authorized platform review
 * ({@link #approve()}); rejections land on {@code REJECTED} with an
 * owner-visible reason ({@link #reject(String)}). Only {@code ACTIVE}
 * organizations are discoverable/bookable. Transitions are explicit —
 * any other move throws {@code IllegalStateException} (the service
 * layer translates it to {@code 409}).
 */
@Entity
@Table(name = "organization")
public class Organization extends BaseAuditableEntity {

	/** Longest owner-visible rejection reason the review API accepts. */
	public static final int MAX_REJECTION_REASON = 500;

	@NotBlank
	@Size(max = 150)
	@Column(name = "name", nullable = false, length = 150)
	private String name;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private OrganizationStatus status = OrganizationStatus.ACTIVE;

	/**
	 * Owner-visible rejection reason, set only by {@link #reject(String)}
	 * and cleared by {@link #approve()}. Internal review notes have no
	 * column — anything stored here is shown to the applicant.
	 */
	@Size(max = 500)
	@Column(name = "rejection_reason", length = 500)
	private String rejectionReason;

	/**
	 * When platform review decided this application (approval or
	 * rejection). Null while pending and for pre-onboarding rows.
	 */
	@Column(name = "decided_at")
	private Instant decidedAt;

	/**
	 * The reviewing platform administrator's user id (audit only).
	 * No JPA association: the reviewer is platform-level and must never
	 * become a tenant leg of this row.
	 */
	@Column(name = "decided_by_user_id")
	private UUID decidedByUserId;

	protected Organization() {
		// JPA only.
	}

	public Organization(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public OrganizationStatus getStatus() {
		return status;
	}

	public void setStatus(OrganizationStatus status) {
		this.status = status;
	}

	public String getRejectionReason() {
		return rejectionReason;
	}

	public Instant getDecidedAt() {
		return decidedAt;
	}

	public UUID getDecidedByUserId() {
		return decidedByUserId;
	}

	/**
	 * Approves a pending application: {@code PENDING_APPROVAL → ACTIVE}.
	 * Clears any rejection reason. The caller owns the transaction,
	 * the verification precondition, and the membership activation —
	 * this method moves the status only.
	 *
	 * @throws IllegalStateException for any non-pending organization.
	 */
	public void approve(UUID reviewerUserId, Instant decidedAt) {
		if (status != OrganizationStatus.PENDING_APPROVAL) {
			throw new IllegalStateException(
					"only pending applications can be approved (was " + status + ")");
		}
		if (reviewerUserId == null || decidedAt == null) {
			throw new IllegalArgumentException("reviewer and decision time are required");
		}
		this.status = OrganizationStatus.ACTIVE;
		this.rejectionReason = null;
		this.decidedAt = decidedAt;
		this.decidedByUserId = reviewerUserId;
	}

	/**
	 * Rejects a pending application: {@code PENDING_APPROVAL → REJECTED}.
	 * The reason is owner-visible (it is returned to the applicant), so
	 * callers must never pass internal notes here.
	 *
	 * @throws IllegalStateException for any non-pending organization.
	 * @throws IllegalArgumentException for a blank or over-long reason.
	 */
	public void reject(String reason, UUID reviewerUserId, Instant decidedAt) {
		if (status != OrganizationStatus.PENDING_APPROVAL) {
			throw new IllegalStateException(
					"only pending applications can be rejected (was " + status + ")");
		}
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("a rejection reason is required");
		}
		String trimmed = reason.trim();
		if (trimmed.length() > MAX_REJECTION_REASON) {
			throw new IllegalArgumentException(
					"rejection reason must be at most " + MAX_REJECTION_REASON + " characters");
		}
		if (reviewerUserId == null || decidedAt == null) {
			throw new IllegalArgumentException("reviewer and decision time are required");
		}
		this.status = OrganizationStatus.REJECTED;
		this.rejectionReason = trimmed;
		this.decidedAt = decidedAt;
		this.decidedByUserId = reviewerUserId;
	}
}
