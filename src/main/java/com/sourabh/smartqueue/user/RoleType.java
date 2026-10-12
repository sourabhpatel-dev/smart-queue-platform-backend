package com.sourabh.smartqueue.user;

/**
 * Platform-level role vocabulary (see ADR-009, ADR-041).
 *
 * <p>Roles are NOT tenant data: the same values apply everywhere, which
 * is why there is no Role entity and no seed rows — grants live in the
 * {@code user_role} join table. Tenant scoping comes from
 * {@code OrganizationMembership} (operational roles) or from the target
 * resource (customers), never from the role itself. Persisted by name;
 * maps directly to future Spring Security authorities (one row → one
 * authority).
 *
 * <p>{@code PLATFORM_ADMIN} is the one principal kind that is not
 * organization-bound: it reviews business applications and nothing
 * else. It is never granted through registration, business
 * applications, invitations, or organization management — the only
 * provisioning path is the {@code app.platform.admin-emails}
 * bootstrap configuration (see ADR-041). It implies no organization
 * membership and no operational access to any tenant.
 */
public enum RoleType {
	CUSTOMER,
	STAFF,
	MANAGER,
	ADMIN,
	PLATFORM_ADMIN
}
