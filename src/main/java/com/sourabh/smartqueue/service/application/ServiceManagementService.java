package com.sourabh.smartqueue.service.application;

import java.time.Duration;
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
import com.sourabh.smartqueue.queue.application.QueueProvisioningService;
import com.sourabh.smartqueue.service.ServiceNameConflictException;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.service.ServiceStatus;
import com.sourabh.smartqueue.service.api.CreateServiceRequest;
import com.sourabh.smartqueue.service.api.ServiceResponse;
import com.sourabh.smartqueue.service.api.UpdateServiceRequest;

/**
 * Service management behind the tenant boundary (see ADR-024).
 *
 * <p>Same shape as branch management: reads keep the display rule,
 * writes need {@code MANAGER}/{@code ADMIN} in the caller's own
 * organization. The branch always comes from the path (list/create) or
 * the row itself (item ops) — never from the request body. Unknown or
 * foreign ids collapse to the same scope-safe {@code 404}; name
 * uniqueness is constraint-arbitrated ({@code 409}); status moves only
 * through the explicit commands (no DELETE).
 */
@Service
public class ServiceManagementService {

	static final String CODE = "SERVICE_NOT_FOUND";

	/** API sort names to persistence properties — the allow-list, nothing else. */
	static final Map<String, String> SORTABLE = Map.of(
			"name", "name",
			"status", "status",
			"durationMinutes", "standardDurationMinutes",
			"createdAt", "createdAt",
			"updatedAt", "updatedAt");

	private final ServiceRepository services;
	private final BranchRepository branches;
	private final QueueProvisioningService queues;
	private final TenantAccessPolicy access;

	public ServiceManagementService(ServiceRepository services,
			BranchRepository branches,
			QueueProvisioningService queues,
			TenantAccessPolicy access) {
		this.services = services;
		this.branches = branches;
		this.queues = queues;
		this.access = access;
	}

	@Transactional(readOnly = true)
	public ApiPage<ServiceResponse> listServices(AuthenticatedPrincipal principal,
			UUID branchId, int page, int size, List<String> sorts, String statusFilter) {
		Branch branch = scopedBranchForRead(principal, branchId);
		Pageable pageable = ApiPageRequest.of(page, size, sorts, SORTABLE, "name");
		// Customers see bookable services: an unfiltered customer listing
		// means ACTIVE only (operational callers keep the raw listing and
		// filter explicitly — management needs to see INACTIVE rows).
		String effectiveStatusFilter = statusFilter;
		if (effectiveStatusFilter == null && !principal.isOperational()) {
			effectiveStatusFilter = "ACTIVE";
		}
		Page<com.sourabh.smartqueue.service.Service> result = effectiveStatusFilter == null
				? services.findByBranchId(branch.getId(), pageable)
				: services.findByBranchIdAndStatus(branch.getId(),
						parseStatus(effectiveStatusFilter), pageable);
		return ApiPage.from(result.map(ServiceResponse::from));
	}

	@Transactional(readOnly = true)
	public ServiceResponse getService(AuthenticatedPrincipal principal, UUID serviceId) {
		return ServiceResponse.from(scopedService(principal, serviceId, false));
	}

	@Transactional
	public ServiceResponse createService(AuthenticatedPrincipal principal, UUID branchId,
			CreateServiceRequest request) {
		Branch branch = scopedBranchForManagement(principal, branchId);
		com.sourabh.smartqueue.service.Service service = new com.sourabh.smartqueue.service.Service(
				branch, request.name(), Duration.ofMinutes(request.durationMinutes()));
		service.setDescription(request.description());
		com.sourabh.smartqueue.service.Service saved = saveOrConflict(service);
		// Queue auto-provisioning (ADR-038): same transaction — the flushed
		// service id is fresh, so the pair cannot pre-exist here; any
		// persistence failure rolls the service creation back with it.
		// No status mirroring: the queue keeps its own ACTIVE default as
		// the walk-in kill-switch; branch/service inactivity is enforced
		// at join time.
		queues.ensureQueueForService(branch.getOrganization(), branch, saved);
		return ServiceResponse.from(saved);
	}

	@Transactional
	public ServiceResponse updateService(AuthenticatedPrincipal principal, UUID serviceId,
			UpdateServiceRequest request) {
		com.sourabh.smartqueue.service.Service service =
				scopedService(principal, serviceId, true);
		service.setName(request.name());
		service.setDescription(request.description());
		service.setStandardDuration(Duration.ofMinutes(request.durationMinutes()));
		return ServiceResponse.from(saveOrConflict(service));
	}

	@Transactional
	public ServiceResponse activateService(AuthenticatedPrincipal principal, UUID serviceId) {
		com.sourabh.smartqueue.service.Service service =
				scopedService(principal, serviceId, true);
		service.setStatus(ServiceStatus.ACTIVE);
		return ServiceResponse.from(services.save(service));
	}

	@Transactional
	public ServiceResponse deactivateService(AuthenticatedPrincipal principal, UUID serviceId) {
		com.sourabh.smartqueue.service.Service service =
				scopedService(principal, serviceId, true);
		service.setStatus(ServiceStatus.INACTIVE);
		return ServiceResponse.from(services.save(service));
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
		// Platform-level customer: reach ACTIVE branches of ACTIVE
		// organizations only (ADR-040) — the branch names the organization.
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
		// Customers reach the management rule (403); it denies them
		// because they hold no organization.
		access.requireBranchManagement(principal, branchId);
		return branch;
	}

	/**
	 * Loads the service for the caller: unknown-or-foreign ids collapse to
	 * the same scope-safe {@code 404}; same-organization rows then pass the
	 * read rule — or, for writes, the management rule.
	 */
	private com.sourabh.smartqueue.service.Service scopedService(
			AuthenticatedPrincipal principal, UUID serviceId, boolean management) {
		com.sourabh.smartqueue.service.Service service = services.findById(serviceId)
				.orElseThrow(() -> new ResourceNotFoundException(CODE, "Service not found"));
		UUID branchId = service.getBranch().getId();
		if (principal.isOperational()) {
			if (!service.getBranch().getOrganization().getId()
					.equals(principal.organizationId())) {
				throw new ResourceNotFoundException(CODE, "Service not found");
			}
			if (management) {
				access.requireBranchManagement(principal, branchId);
			} else {
				access.requireBranchAccess(principal, branchId);
			}
			return service;
		}
		// Platform-level customer (ADR-040): reads reach ACTIVE services
		// of ACTIVE branches of ACTIVE organizations; writes reach the
		// management rule, which denies them explicitly (403) because
		// they hold no organization.
		if (management) {
			if (service.getBranch().getOrganization().getStatus()
					!= com.sourabh.smartqueue.organization.OrganizationStatus.ACTIVE) {
				throw new ResourceNotFoundException(CODE, "Service not found");
			}
			access.requireBranchManagement(principal, branchId);
			return service;
		}
		if (service.getStatus() != ServiceStatus.ACTIVE) {
			throw new ResourceNotFoundException(CODE, "Service not found");
		}
		CustomerScope.requireActiveBranch(service.getBranch(), CODE, "Service not found");
		return service;
	}

	private ServiceStatus parseStatus(String statusFilter) {
		try {
			return ServiceStatus.valueOf(statusFilter.trim().toUpperCase());
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
	private com.sourabh.smartqueue.service.Service saveOrConflict(
			com.sourabh.smartqueue.service.Service service) {
		try {
			return services.saveAndFlush(service);
		} catch (DataIntegrityViolationException e) {
			throw new ServiceNameConflictException(
					"Service name already exists at this branch");
		}
	}
}

