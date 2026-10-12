package com.sourabh.smartqueue.common;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchStatus;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.organization.OrganizationStatus;

/**
 * Customer-reachability rule for platform-level identities (ADR-040).
 *
 * <p>A customer belongs to no organization, so organization-scoped branch
 * equality does not apply to them. Instead the resource itself names the
 * organization for the request: a customer may reach only an ACTIVE
 * branch of an ACTIVE organization. Everything else is a scope-safe
 * not-found — the customer learns nothing about inactive resources, and
 * reaching a branch never implies membership, authorization, or any
 * privilege there.
 *
 * <p>Operational principals do not use this guard: their scope comes from
 * an ACTIVE {@code OrganizationMembership} via
 * {@code TenantAccessPolicy}.
 */
public final class CustomerScope {

	private CustomerScope() {
	}

	public static void requireActiveBranch(Branch branch, String code, String message) {
		if (branch.getStatus() != BranchStatus.ACTIVE
				|| branch.getOrganization().getStatus() != OrganizationStatus.ACTIVE) {
			throw new ResourceNotFoundException(code, message);
		}
	}
}
