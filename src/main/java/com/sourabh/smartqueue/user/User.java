package com.sourabh.smartqueue.user;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import com.sourabh.smartqueue.common.BaseAuditableEntity;
import com.sourabh.smartqueue.organization.Organization;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An application-level person/account (see ADR-009, ADR-040).
 *
 * <p>Email is the globally unique identity (one email → one account, so a
 * future login lookup is unambiguous); it is normalized to lowercase on the
 * way in. The UUID remains the primary key; display names carry no
 * uniqueness.
 *
 * <p>Roles are a platform-level vocabulary held as a string-persisted set on
 * the join table {@code user_role} — multi-role from day one with no seed
 * data and no Role entity (see ADR-009 for why). No password or
 * authentication fields: those belong to the security phase
 * ({@code user_credential}, ADR-021).
 *
 * <p><strong>Organization (ADR-040, Phase 4R.2):</strong> since V20 the
 * column is nullable and the membership model
 * ({@code OrganizationMembership}) is the authorization fact for
 * organization-scoped operational access. A platform-level customer has
 * {@code organization = null}; operational users (STAFF/MANAGER/ADMIN) keep
 * a populated primary organization here. The column is a legacy
 * compatibility anchor — the running application (principal, JWT,
 * {@code staff_assignment} composite FK) still reads it until the 4R.3+
 * authentication phases migrate; it is NOT the authority for access.
 * Unidirectional lazy {@code User → Organization}, no cascade — the Phase
 * 2A/2B conventions. Branch access for staff goes through
 * {@code StaffAssignment}, never through this entity.
 */
@Entity
@Table(name = "app_user")
public class User extends BaseAuditableEntity {

	// No @NotNull: since V20 a platform-level customer (organization_id
	// NULL) is a valid, intended shape — Bean Validation runs on persist
	// and must not reject it.
	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(
			name = "organization_id",
			nullable = true,
			foreignKey = @ForeignKey(name = "fk_user_organization"))
	private Organization organization;

	@NotBlank
	@Email
	@Size(max = 255)
	@Column(name = "email", nullable = false, length = 255, unique = true)
	private String email;

	@NotBlank
	@Size(max = 150)
	@Column(name = "display_name", nullable = false, length = 150)
	private String displayName;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(
			name = "user_role",
			joinColumns = @JoinColumn(
					name = "user_id",
					nullable = false,
					foreignKey = @ForeignKey(name = "fk_user_role_user")))
	@Column(name = "role", nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	private Set<RoleType> roles = EnumSet.noneOf(RoleType.class);

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private UserStatus status = UserStatus.ACTIVE;

	protected User() {
		// JPA only.
	}

	/**
	 * An organization-owned account (operational users; customers
	 * registered through the current ADR-036-shaped registration flow).
	 * Retained for compatibility until the 4R.3+ authentication phases.
	 */
	public User(Organization organization, String email, String displayName) {
		this.organization = organization;
		setEmail(email);
		this.displayName = displayName;
	}

	/**
	 * A platform-level account with no organization — the customer shape
	 * ADR-040 makes structurally possible (organization_id NULL, no
	 * membership row). Not yet produced by registration (Phase 4R.4);
	 * the constructor exists so the persistence model is complete.
	 */
	public User(String email, String displayName) {
		setEmail(email);
		this.displayName = displayName;
	}

	public Organization getOrganization() {
		return organization;
	}

	public void setOrganization(Organization organization) {
		this.organization = organization;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = normalizeEmail(email);
	}

	/**
	 * The single email-normalization rule (see ADR-009, reused by ADR-021):
	 * trim + lowercase. Login lookup must use this exact form — never a
	 * second normalization algorithm.
	 */
	public static String normalizeEmail(String email) {
		return email == null ? null : email.trim().toLowerCase();
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public Set<RoleType> getRoles() {
		return Collections.unmodifiableSet(roles);
	}

	public void grantRole(RoleType role) {
		roles.add(role);
	}

	public void revokeRole(RoleType role) {
		roles.remove(role);
	}

	public UserStatus getStatus() {
		return status;
	}

	public void setStatus(UserStatus status) {
		this.status = status;
	}
}
