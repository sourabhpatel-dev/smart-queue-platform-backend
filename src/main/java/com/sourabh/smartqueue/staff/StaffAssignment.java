package com.sourabh.smartqueue.staff;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.common.BaseAuditableEntity;
import com.sourabh.smartqueue.organization.Organization;
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
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

/**
 * Records that a user is assigned to work at a branch:
 * "Asha staffs Downtown" (see ADR-009).
 *
 * <p>The row carries the shared {@code organization_id}, and the V4
 * migration defines <em>composite</em> foreign keys
 * {@code (user_id, organization_id)} and
 * {@code (branch_id, organization_id)}, so a user from Organization A can
 * never be assigned to a branch from Organization B — enforced by the
 * database on every code path, mirroring the Phase 2B capability pattern.
 * The JPA mapping stays simple (one join column per association); the
 * migration is the authority on the composite keys. A constructor guard
 * fails fast with a clear message; the composites are the backstop.
 *
 * <p>One row per (user, branch): {@code uq_staff_user_branch} rejects
 * duplicates. Organization is intentionally NOT part of that key — the
 * branch already pins the organization, so adding it would be redundant.
 * Unidirectional, lazy, no cascade.
 */
@Entity
@Table(
		name = "staff_assignment",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_staff_user_branch",
				columnNames = {"user_id", "branch_id"}))
public class StaffAssignment extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "organization_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_staff_organization"))
	private Organization organization;

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "user_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_staff_user"))
	private User user;

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "branch_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_staff_branch"))
	private Branch branch;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private StaffAssignmentStatus status = StaffAssignmentStatus.ACTIVE;

	protected StaffAssignment() {
		// JPA only.
	}

	/**
	 * @throws IllegalArgumentException if user and branch belong to
	 * different organizations (or to a different organization than given).
	 * The database composite foreign keys enforce the same rule
	 * independently (see V4 migration).
	 */
	public StaffAssignment(Organization organization, User user, Branch branch) {
		// A user without an organization (a platform-level customer, or an
		// operational user whose membership is gone) can never be staffed
		// to a branch (ADR-040).
		if (user.getOrganization() == null
				|| !user.getOrganization().getId().equals(organization.getId())
				|| !branch.getOrganization().getId().equals(organization.getId())) {
			throw new IllegalArgumentException(
					"user, branch and assignment must belong to the same organization");
		}
		this.organization = organization;
		this.user = user;
		this.branch = branch;
	}

	public Organization getOrganization() {
		return organization;
	}

	public User getUser() {
		return user;
	}

	public Branch getBranch() {
		return branch;
	}

	public StaffAssignmentStatus getStatus() {
		return status;
	}

	public void setStatus(StaffAssignmentStatus status) {
		this.status = status;
	}
}
