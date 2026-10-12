package com.sourabh.smartqueue.branch.application;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.TenantAccessPolicy;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchNameConflictException;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.branch.BranchStatus;
import com.sourabh.smartqueue.branch.InvalidBranchRequestException;
import com.sourabh.smartqueue.branch.api.BranchResponse;
import com.sourabh.smartqueue.branch.api.CreateBranchRequest;
import com.sourabh.smartqueue.branch.api.UpdateBranchRequest;
import com.sourabh.smartqueue.common.CustomerScope;
import com.sourabh.smartqueue.common.api.ApiPage;
import com.sourabh.smartqueue.common.api.ApiPageRequest;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;

/**
 * Branch management behind the tenant boundary (see ADR-023).
 *
 * <p>Reads follow the Phase 3F display rule (managers/admins org-wide,
 * staff by ACTIVE assignment, customers observe their own organization's
 * branches). Writes (create/update/activate/deactivate) require
 * {@code MANAGER} or {@code ADMIN} in the caller's own organization —
 * staff and customers are denied even at their own branches.
 *
 * <p>Scope order is always organization first: unknown-or-foreign ids
 * yield the same scope-safe {@code 404}. The organization always comes
 * from the principal — request bodies carry no tenant ids. Name
 * uniqueness is enforced by the {@code uq_branch_organization_name}
 * constraint (the pre-check-free save translates violations to
 * {@code 409}); cross-organization duplicates stay legal. Status moves
 * only through the explicit activate/deactivate commands — never through
 * update payloads, never to another organization, never deleted.
 */
@Service
public class BranchService {

	static final String CODE = "BRANCH_NOT_FOUND";

	/** API sort names to persistence properties — the allow-list, nothing else. */
	static final Map<String, String> SORTABLE = Map.of(
			"name", "name",
			"status", "status",
			"createdAt", "createdAt",
			"updatedAt", "updatedAt");

	private final BranchRepository branches;
	private final OrganizationRepository organizations;
	private final TenantAccessPolicy access;

	public BranchService(BranchRepository branches,
			OrganizationRepository organizations,
			TenantAccessPolicy access) {
		this.branches = branches;
		this.organizations = organizations;
		this.access = access;
	}

	@Transactional(readOnly = true)
	public BranchResponse getBranch(AuthenticatedPrincipal principal, UUID branchId) {
		return BranchResponse.from(scopedBranch(principal, branchId, false));
	}

	@Transactional(readOnly = true)
	public ApiPage<BranchResponse> listBranches(AuthenticatedPrincipal principal,
			int page, int size, List<String> sorts, String statusFilter) {
		Pageable pageable = ApiPageRequest.of(page, size, sorts, SORTABLE, "name");
		// Platform-level customers have no organization to list from and
		// discover organizations through the public directory instead
		// (ADR-040); the endpoint stays organization-scoped for
		// operational callers and answers an empty page for customers.
		if (!principal.hasOrganization()) {
			return ApiPage.from(Page.empty(pageable));
		}
		UUID organizationId = principal.organizationId();
		Page<Branch> result = statusFilter == null
				? branches.findByOrganizationId(organizationId, pageable)
				: branches.findByOrganizationIdAndStatus(organizationId,
						parseStatus(statusFilter), pageable);
		return ApiPage.from(result.map(BranchResponse::from));
	}

	@Transactional
	public BranchResponse createBranch(AuthenticatedPrincipal principal,
			CreateBranchRequest request) {
		access.requireOrganizationManagement(principal);
		Organization organization = organizations.findById(principal.organizationId())
				.orElseThrow(() -> new ResourceNotFoundException(
						"ORGANIZATION_NOT_FOUND", "Organization not found"));
		Branch branch = new Branch(organization, request.name(), request.city(),
				request.country(), zoneId(request.timeZone()));
		applyMutableFields(branch, request.name(), request.addressLine1(),
				request.addressLine2(), request.city(), request.state(),
				request.postalCode(), request.country(), request.timeZone());
		return BranchResponse.from(saveOrConflict(branch));
	}

	@Transactional
	public BranchResponse updateBranch(AuthenticatedPrincipal principal, UUID branchId,
			UpdateBranchRequest request) {
		Branch branch = scopedBranch(principal, branchId, true);
		applyMutableFields(branch, request.name(), request.addressLine1(),
				request.addressLine2(), request.city(), request.state(),
				request.postalCode(), request.country(), request.timeZone());
		return BranchResponse.from(saveOrConflict(branch));
	}

	@Transactional
	public BranchResponse activateBranch(AuthenticatedPrincipal principal, UUID branchId) {
		Branch branch = scopedBranch(principal, branchId, true);
		branch.setStatus(BranchStatus.ACTIVE);
		return BranchResponse.from(branches.save(branch));
	}

	@Transactional
	public BranchResponse deactivateBranch(AuthenticatedPrincipal principal, UUID branchId) {
		Branch branch = scopedBranch(principal, branchId, true);
		branch.setStatus(BranchStatus.INACTIVE);
		return BranchResponse.from(branches.save(branch));
	}

	/**
	 * Loads the branch for the caller: unknown-or-foreign ids collapse to
	 * the same scope-safe {@code 404}; same-organization ids then pass the
	 * read rule — or, for writes, the management rule. Platform-level
	 * customers reach ACTIVE branches of ACTIVE organizations for reads
	 * (ADR-040); writes always reach the management rule, which denies
	 * customers explicitly ({@code 403}) because they hold no
	 * organization to manage.
	 */
	private Branch scopedBranch(AuthenticatedPrincipal principal, UUID branchId,
			boolean management) {
		Branch branch = branches.findById(branchId)
				.orElseThrow(() -> new ResourceNotFoundException(CODE, "Branch not found"));
		if (principal.isOperational()) {
			if (!branch.getOrganization().getId().equals(principal.organizationId())) {
				throw new ResourceNotFoundException(CODE, "Branch not found");
			}
		} else if (!management) {
			CustomerScope.requireActiveBranch(branch, CODE, "Branch not found");
			return branch;
		} else if (branch.getOrganization().getStatus()
				!= com.sourabh.smartqueue.organization.OrganizationStatus.ACTIVE) {
			throw new ResourceNotFoundException(CODE, "Branch not found");
		}
		if (management) {
			access.requireBranchManagement(principal, branchId);
		} else {
			access.requireBranchAccess(principal, branchId);
		}
		return branch;
	}

	private void applyMutableFields(Branch branch, String name, String addressLine1,
			String addressLine2, String city, String state, String postalCode,
			String country, String timeZone) {
		branch.setName(name);
		branch.setAddressLine1(addressLine1);
		branch.setAddressLine2(addressLine2);
		branch.setCity(city);
		branch.setState(state);
		branch.setPostalCode(postalCode);
		branch.setCountry(country);
		branch.setTimeZone(zoneId(timeZone));
	}

	private ZoneId zoneId(String timeZone) {
		try {
			return ZoneId.of(timeZone);
		} catch (DateTimeException e) {
			throw new InvalidBranchRequestException("Invalid time zone: " + timeZone);
		}
	}

	private BranchStatus parseStatus(String statusFilter) {
		try {
			return BranchStatus.valueOf(statusFilter.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new InvalidRequestException("INVALID_STATUS",
					"Unsupported status filter: " + statusFilter
							+ ". Supported: ACTIVE, INACTIVE.");
		}
	}

	/**
	 * Persists with the unique constraint as the arbiter: no
	 * check-then-insert (which races), just the flush inside the try so a
	 * duplicate name surfaces here as a stable {@code 409}.
	 */
	private Branch saveOrConflict(Branch branch) {
		try {
			return branches.saveAndFlush(branch);
		} catch (DataIntegrityViolationException e) {
			throw new BranchNameConflictException(
					"Branch name already exists in this organization");
		}
	}
}

