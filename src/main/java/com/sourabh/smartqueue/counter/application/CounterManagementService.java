package com.sourabh.smartqueue.counter.application;

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
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.common.api.ApiPage;
import com.sourabh.smartqueue.common.api.ApiPageRequest;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.CustomerScope;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.counter.CounterNameConflictException;
import com.sourabh.smartqueue.counter.CounterStatus;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.counter.api.CounterResponse;
import com.sourabh.smartqueue.counter.api.CreateCounterRequest;
import com.sourabh.smartqueue.counter.api.UpdateCounterRequest;

/**
 * Counter management behind the tenant boundary (see ADR-024).
 *
 * <p>Same shape as service management: reads keep the display rule,
 * writes need {@code MANAGER}/{@code ADMIN} in the caller's own
 * organization. The branch always comes from the path (list/create) or
 * the row itself (item ops) — never from the request body. Served
 * services are managed through the capability endpoints, never through
 * these payloads. Status moves only through the explicit commands, and
 * deactivation never removes capabilities (no DELETE anywhere).
 */
@Service
public class CounterManagementService {

	static final String CODE = "COUNTER_NOT_FOUND";

	/** API sort names to persistence properties — the allow-list, nothing else. */
	static final Map<String, String> SORTABLE = Map.of(
			"name", "name",
			"status", "status",
			"createdAt", "createdAt",
			"updatedAt", "updatedAt");

	private final ServiceCounterRepository counters;
	private final BranchRepository branches;
	private final TenantAccessPolicy access;

	public CounterManagementService(ServiceCounterRepository counters,
			BranchRepository branches,
			TenantAccessPolicy access) {
		this.counters = counters;
		this.branches = branches;
		this.access = access;
	}

	@Transactional(readOnly = true)
	public ApiPage<CounterResponse> listCounters(AuthenticatedPrincipal principal,
			UUID branchId, int page, int size, List<String> sorts, String statusFilter) {
		Branch branch = scopedBranchForRead(principal, branchId);
		Pageable pageable = ApiPageRequest.of(page, size, sorts, SORTABLE, "name");
		Page<ServiceCounter> result = statusFilter == null
				? counters.findByBranchId(branch.getId(), pageable)
				: counters.findByBranchIdAndStatus(branch.getId(),
						parseStatus(statusFilter), pageable);
		return ApiPage.from(result.map(CounterResponse::from));
	}

	@Transactional(readOnly = true)
	public CounterResponse getCounter(AuthenticatedPrincipal principal, UUID counterId) {
		return CounterResponse.from(scopedCounter(principal, counterId, false));
	}

	@Transactional
	public CounterResponse createCounter(AuthenticatedPrincipal principal, UUID branchId,
			CreateCounterRequest request) {
		Branch branch = scopedBranchForManagement(principal, branchId);
		return CounterResponse.from(
				saveOrConflict(new ServiceCounter(branch, request.name())));
	}

	@Transactional
	public CounterResponse updateCounter(AuthenticatedPrincipal principal, UUID counterId,
			UpdateCounterRequest request) {
		ServiceCounter counter = scopedCounter(principal, counterId, true);
		counter.setName(request.name());
		return CounterResponse.from(saveOrConflict(counter));
	}

	@Transactional
	public CounterResponse activateCounter(AuthenticatedPrincipal principal, UUID counterId) {
		ServiceCounter counter = scopedCounter(principal, counterId, true);
		counter.setStatus(CounterStatus.ACTIVE);
		return CounterResponse.from(counters.save(counter));
	}

	@Transactional
	public CounterResponse deactivateCounter(AuthenticatedPrincipal principal, UUID counterId) {
		ServiceCounter counter = scopedCounter(principal, counterId, true);
		counter.setStatus(CounterStatus.INACTIVE);
		return CounterResponse.from(counters.save(counter));
	}

	private Branch scopedBranchForRead(AuthenticatedPrincipal principal, UUID branchId) {
		Branch branch = branches.findById(branchId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"BRANCH_NOT_FOUND", "Branch not found"));
		if (principal.isOperational()) {
			if (!branch.getOrganization().getId().equals(principal.organizationId())) {
				throw new ResourceNotFoundException("BRANCH_NOT_FOUND", "Branch not found");
			}
			access.requireBranchAccess(principal, branchId);
			return branch;
		}
		CustomerScope.requireActiveBranch(branch, "BRANCH_NOT_FOUND", "Branch not found");
		return branch;
	}

	private Branch scopedBranchForManagement(AuthenticatedPrincipal principal, UUID branchId) {
		Branch branch = branches.findById(branchId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"BRANCH_NOT_FOUND", "Branch not found"));
		if (principal.isOperational()) {
			if (!branch.getOrganization().getId().equals(principal.organizationId())) {
				throw new ResourceNotFoundException("BRANCH_NOT_FOUND", "Branch not found");
			}
		} else if (branch.getOrganization().getStatus()
				!= com.sourabh.smartqueue.organization.OrganizationStatus.ACTIVE) {
			throw new ResourceNotFoundException("BRANCH_NOT_FOUND", "Branch not found");
		}
		// Customers reach the management rule (403); they hold no
		// organization to manage.
		access.requireBranchManagement(principal, branchId);
		return branch;
	}

	/**
	 * Loads the counter for the caller: unknown-or-foreign ids collapse to
	 * the same scope-safe {@code 404}; same-organization rows then pass the
	 * read rule — or, for writes, the management rule. Customers reach
	 * ACTIVE counters of ACTIVE branches/organizations (ADR-040); writes
	 * always reach the management rule, which denies them explicitly.
	 */
	private ServiceCounter scopedCounter(AuthenticatedPrincipal principal, UUID counterId,
			boolean management) {
		ServiceCounter counter = counters.findById(counterId)
				.orElseThrow(() -> new ResourceNotFoundException(CODE, "Counter not found"));
		UUID branchId = counter.getBranch().getId();
		if (principal.isOperational()) {
			if (!counter.getBranch().getOrganization().getId()
					.equals(principal.organizationId())) {
				throw new ResourceNotFoundException(CODE, "Counter not found");
			}
		} else if (management) {
			if (counter.getBranch().getOrganization().getStatus()
					!= com.sourabh.smartqueue.organization.OrganizationStatus.ACTIVE) {
				throw new ResourceNotFoundException(CODE, "Counter not found");
			}
		} else {
			if (counter.getStatus() != CounterStatus.ACTIVE) {
				throw new ResourceNotFoundException(CODE, "Counter not found");
			}
			CustomerScope.requireActiveBranch(counter.getBranch(), CODE, "Counter not found");
		}
		if (management) {
			access.requireBranchManagement(principal, branchId);
		} else if (principal.isOperational()) {
			// Customers observe ACTIVE counters/displays without a policy
			// gate (ADR-040); the ACTIVE guards above are the boundary.
			access.requireBranchAccess(principal, branchId);
		}
		return counter;
	}

	private CounterStatus parseStatus(String statusFilter) {
		try {
			return CounterStatus.valueOf(statusFilter.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new InvalidRequestException("INVALID_STATUS",
					"Unsupported status filter: " + statusFilter
							+ ". Supported: ACTIVE, INACTIVE, MAINTENANCE.");
		}
	}

	/**
	 * Persists with the unique constraint as the arbiter: no
	 * check-then-insert (which races), just the flush inside the try so a
	 * duplicate name surfaces here as a stable {@code 409}.
	 */
	private ServiceCounter saveOrConflict(ServiceCounter counter) {
		try {
			return counters.saveAndFlush(counter);
		} catch (DataIntegrityViolationException e) {
			throw new CounterNameConflictException(
					"Counter name already exists at this branch");
		}
	}
}

