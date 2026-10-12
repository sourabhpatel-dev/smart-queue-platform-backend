package com.sourabh.smartqueue.membership;

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
 * "User U holds operational role R at organization O" — the authorization
 * fact for organization-scoped access (see ADR-040, Phase 4R.2).
 *
 * <p>Distinct from {@code StaffAssignment} (which pins a user to a
 * <em>branch</em>): membership is per organization and carries the role;
 * the branch assignment remains the branch-level detail. One row per
 * (user, organization) — {@code uq_membership_user_organization} rejects
 * duplicates, so the pair, never the email and never a client-supplied
 * value, is the identity of a grant.
 *
 * <p>Customers hold no membership rows at all: selecting an organization to
 * book or join a queue is request context, not membership. That is what
 * lets one platform-level account operate at multiple organizations.
 *
 * <p>Unidirectional lazy associations, no cascade — the established
 * conventions. No guard forces {@code user.organizationId} to equal the
 * membership's organization: the legacy home-organization column is not
 * the authority (this table is), and a multi-organization membership set
 * is the target model, not an error.
 */
@Entity
@Table(
		name = "organization_membership",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_membership_user_organization",
				columnNames = {"user_id", "organization_id"}))
public class OrganizationMembership extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "user_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_membership_user"))
	private User user;

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "organization_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_membership_organization"))
	private Organization organization;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 20)
	private MembershipRole role;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private MembershipStatus status = MembershipStatus.ACTIVE;

	protected OrganizationMembership() {
		// JPA only.
	}

	/**
	 * @throws IllegalArgumentException if any leg is null. Role and
	 * organization agreement with the user's legacy home organization are
	 * deliberately not enforced here — see the class javadoc.
	 */
	public OrganizationMembership(User user, Organization organization,
			MembershipRole role) {
		if (user == null || organization == null || role == null) {
			throw new IllegalArgumentException(
					"membership user, organization and role are required");
		}
		this.user = user;
		this.organization = organization;
		this.role = role;
	}

	public User getUser() {
		return user;
	}

	public Organization getOrganization() {
		return organization;
	}

	public MembershipRole getRole() {
		return role;
	}

	/**
	 * Role changes are an administrative act through the user-management
	 * surface (ADR-040): the unique (user, organization) keeps one row, so
	 * the grant updates in place.
	 */
	public void setRole(MembershipRole role) {
		this.role = role;
	}

	public MembershipStatus getStatus() {
		return status;
	}

	public void setStatus(MembershipStatus status) {
		this.status = status;
	}
}
