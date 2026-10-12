package com.sourabh.smartqueue.auth;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.sourabh.smartqueue.user.RoleType;

/**
 * Lightweight authenticated identity (see ADR-021, ADR-040).
 *
 * <p>Carries only what security and application boundaries need: who the
 * caller is ({@code userId}, {@code email}), and what vocabulary they hold
 * ({@code roles}). Never a JPA entity, never a password hash, never
 * appointments/entries/assignments, never Redis or HTTP objects.
 *
 * <p><strong>Organization context (ADR-040):</strong> {@code organizationId}
 * is the <em>resolved operational organization</em> and is nullable:
 * <ul>
 *   <li>Customers are platform-level identities — {@code organizationId}
 *   is {@code null}, the JWT carries no {@code org} claim, and no
 *   {@code TenantContext} is installed for their requests. Their
 *   organization-specific actions derive scope from the target resource
 *   (branch/queue/appointment), never from this field.</li>
 *   <li>Operational users (STAFF/MANAGER/ADMIN) carry the organization
 *   resolved from an ACTIVE {@code OrganizationMembership} at login; the
 *   legacy {@code app_user.organization_id} column is never consulted for
 *   customer authorization and only serves as the login-time preference
 *   among a user's active memberships.</li>
 * </ul>
 *
 * <p>Roles stay the canonical {@code RoleType} vocabulary — no second
 * persisted enum. The {@code ROLE_}-prefixed authorities are an in-memory
 * Spring Security representation built on demand, never stored.
 */
public record AuthenticatedPrincipal(
		UUID userId,
		UUID organizationId,
		String email,
		Set<RoleType> roles) {

	/**
	 * Spring Security authorities for this principal: one
	 * {@code ROLE_<NAME>} per granted role. Built on demand, held nowhere.
	 */
	public List<SimpleGrantedAuthority> toAuthorities() {
		return roles.stream()
				.map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
				.toList();
	}

	public boolean hasAnyRole(RoleType... candidates) {
		for (RoleType candidate : candidates) {
			if (roles.contains(candidate)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * True when this principal holds at least one organization-scoped
	 * operational role (STAFF/MANAGER/ADMIN). Customers hold only CUSTOMER.
	 */
	public boolean isOperational() {
		return hasAnyRole(RoleType.STAFF, RoleType.MANAGER, RoleType.ADMIN);
	}

	/** True when an operational organization context was resolved. */
	public boolean hasOrganization() {
		return organizationId != null;
	}
}
