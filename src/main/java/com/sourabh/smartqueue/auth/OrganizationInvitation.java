package com.sourabh.smartqueue.auth;

import java.time.Instant;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.common.BaseAuditableEntity;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

/**
 * An employee invitation into one organization (see ADR-037).
 *
 * <p>The invitation names everything the invitee cannot change at
 * accept time: organization, email, role ({@code MANAGER} or
 * {@code STAFF} — never {@code ADMIN}, never {@code CUSTOMER}), and
 * the branch for staff assignments (required for {@code STAFF},
 * ignored for {@code MANAGER}). Email is stored normalized with the
 * single {@code User} rule. Liveness is a predicate
 * ({@code acceptedAt IS NULL AND revokedAt IS NULL AND expiresAt >
 * now}); resend revokes prior live rows for the same
 * (organization, email) before issuing. Acceptance is one-way.
 */
@Entity
@Table(name = "organization_invitation")
public class OrganizationInvitation extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "organization_id",
			nullable = false,
			updatable = false,
			foreignKey = @ForeignKey(name = "fk_invitation_organization"))
	private Organization organization;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(
			name = "branch_id",
			updatable = false,
			foreignKey = @ForeignKey(name = "fk_invitation_branch_organization"))
	private Branch branch;

	@NotNull
	@Column(name = "email", nullable = false, length = 255, updatable = false)
	private String email;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 20, updatable = false)
	private RoleType role;

	@NotNull
	@Column(name = "token_hash", nullable = false, length = 64, updatable = false)
	private String tokenHash;

	@NotNull
	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "accepted_at")
	private Instant acceptedAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(
			name = "created_by_user_id",
			updatable = false,
			foreignKey = @ForeignKey(name = "fk_invitation_created_by"))
	private User createdBy;

	protected OrganizationInvitation() {
		// JPA only.
	}

	public OrganizationInvitation(Organization organization, Branch branch, String email,
			RoleType role, String tokenHash, Instant expiresAt, User createdBy) {
		if (organization == null) {
			throw new IllegalArgumentException("organization is required");
		}
		if (email == null || email.isBlank()) {
			throw new IllegalArgumentException("email is required");
		}
		if (role != RoleType.MANAGER && role != RoleType.STAFF) {
			throw new IllegalArgumentException(
					"invitations support MANAGER and STAFF only (was " + role + ")");
		}
		if (role == RoleType.STAFF && branch == null) {
			throw new IllegalArgumentException("staff invitations require a branch");
		}
		if (branch != null && !branch.getOrganization().getId().equals(organization.getId())) {
			throw new IllegalArgumentException("invitation branch must belong to the organization");
		}
		if (tokenHash == null || tokenHash.isBlank()) {
			throw new IllegalArgumentException("token hash is required");
		}
		if (expiresAt == null) {
			throw new IllegalArgumentException("expiry is required");
		}
		this.organization = organization;
		this.branch = branch;
		this.email = User.normalizeEmail(email);
		this.role = role;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
		this.createdBy = createdBy;
	}

	public Organization getOrganization() {
		return organization;
	}

	public Branch getBranch() {
		return branch;
	}

	public String getEmail() {
		return email;
	}

	public RoleType getRole() {
		return role;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getAcceptedAt() {
		return acceptedAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public User getCreatedBy() {
		return createdBy;
	}

	public boolean isLive(Instant now) {
		return acceptedAt == null && revokedAt == null && expiresAt.isAfter(now);
	}

	/** One-way: an accepted invitation never reopens. */
	public void markAccepted(Instant acceptedAt) {
		if (this.acceptedAt != null) {
			throw new IllegalStateException("invitation already accepted");
		}
		this.acceptedAt = acceptedAt;
	}

	/** One-way: a revoked invitation never revives. */
	public void markRevoked(Instant revokedAt) {
		if (this.acceptedAt != null) {
			throw new IllegalStateException("invitation already accepted");
		}
		this.revokedAt = revokedAt;
	}
}
