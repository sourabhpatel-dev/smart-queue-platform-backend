package com.sourabh.smartqueue.tenant;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable tenant scope for application-level isolation (see ADR-019).
 *
 * <p>Carries only the two scope identifiers — never a User, Principal, JWT,
 * HTTP request, or any mutable authorization state. The context is the
 * <em>where</em>, not the <em>who</em> or the <em>what</em>: authentication
 * and authorization remain separate concerns that future security code
 * combines with this object.
 *
 * <p>Scope model:
 * <ul>
 *   <li>{@code organizationId} — always required. Every tenant-owned
 *   resource traces back to exactly one organization.</li>
 *   <li>{@code branchId} — optional. When present, the scope is narrowed to
 *   one branch within the organization. When absent, the scope spans all
 *   branches of the organization (organization-level access).</li>
 * </ul>
 *
 * <p>Branch implies organization: a context with a branchId must also carry
 * the branch's organizationId. The holder and application services enforce
 * this; the record itself stores both independently so the relationship can
 * be validated at the boundary.
 */
public record TenantContext(UUID organizationId, UUID branchId) {

	/**
	 * @throws NullPointerException if organizationId is null. A tenant context
	 * without an organization is meaningless — fail fast rather than
	 * silently interpreting null as "all organizations".
	 */
	public TenantContext {
		Objects.requireNonNull(organizationId, "organizationId must not be null");
	}

	/**
	 * @return true when this context is narrowed to a single branch.
	 */
	public boolean isBranchScoped() {
		return branchId != null;
	}

	/**
	 * @return true when the given branchId matches this context's branch,
	 * or when this context is organization-level (branchId is null) and the
	 * given branch belongs to the context's organization.
	 */
	public boolean includesBranch(UUID candidateBranchId) {
		if (branchId == null) {
			return true;
		}
		return branchId.equals(candidateBranchId);
	}
}
