package com.sourabh.smartqueue.counter.application;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.TenantAccessPolicy;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.common.CustomerScope;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.counter.CapabilityAlreadyExistsException;
import com.sourabh.smartqueue.counter.CapabilityBranchMismatchException;
import com.sourabh.smartqueue.counter.CapabilityNotFoundException;
import com.sourabh.smartqueue.counter.CounterStatus;
import com.sourabh.smartqueue.counter.CounterCapability;
import com.sourabh.smartqueue.counter.CounterCapabilityRepository;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.counter.api.CapabilityResponse;
import com.sourabh.smartqueue.service.ServiceRepository;

/**
 * Capability management behind the tenant boundary (see ADR-024).
 *
 * <p>Invariants, in check order: the counter must be the caller's
 * (unknown-or-foreign → scope-safe {@code 404}, then the read or
 * management rule by operation); the service must exist in the caller's
 * organization (same {@code 404} discipline); and both rows must share
 * the counter's branch — cross-branch links are rejected with {@code 422}
 * before the database, whose composite foreign keys remain the backstop.
 * Duplicate adds are constraint-arbitrated ({@code 409}); removal deletes
 * only the association row (missing → {@code 404}, never the service, the
 * counter, or any appointment/queue/session state).
 */
@Service
public class CounterCapabilityService {

	static final String COUNTER_CODE = "COUNTER_NOT_FOUND";
	static final String SERVICE_CODE = "SERVICE_NOT_FOUND";

	private final CounterCapabilityRepository capabilities;
	private final ServiceCounterRepository counters;
	private final ServiceRepository services;
	private final TenantAccessPolicy access;

	public CounterCapabilityService(CounterCapabilityRepository capabilities,
			ServiceCounterRepository counters,
			ServiceRepository services,
			TenantAccessPolicy access) {
		this.capabilities = capabilities;
		this.counters = counters;
		this.services = services;
		this.access = access;
	}

	@Transactional(readOnly = true)
	public List<CapabilityResponse> listCapabilities(AuthenticatedPrincipal principal,
			UUID counterId) {
		ServiceCounter counter = scopedCounter(principal, counterId, false);
		return capabilities.findByCounterId(counter.getId()).stream()
				.map(CapabilityResponse::from)
				.sorted(Comparator.comparing(CapabilityResponse::serviceName)
						.thenComparing(CapabilityResponse::serviceId))
				.toList();
	}

	@Transactional
	public CapabilityResponse addCapability(AuthenticatedPrincipal principal,
			UUID counterId, UUID serviceId) {
		ServiceCounter counter = scopedCounter(principal, counterId, true);
		com.sourabh.smartqueue.service.Service service = services.findById(serviceId)
				.orElseThrow(() -> new ResourceNotFoundException(
						SERVICE_CODE, "Service not found"));
		if (!service.getBranch().getOrganization().getId()
				.equals(principal.organizationId())) {
			throw new ResourceNotFoundException(SERVICE_CODE, "Service not found");
		}
		Branch branch = counter.getBranch();
		if (!service.getBranch().getId().equals(branch.getId())) {
			throw new CapabilityBranchMismatchException(
					"Service belongs to another branch and cannot be served by this counter");
		}
		try {
			return CapabilityResponse.from(capabilities.saveAndFlush(
					new CounterCapability(branch, service, counter)));
		} catch (DataIntegrityViolationException e) {
			throw new CapabilityAlreadyExistsException(
					"Counter already serves this service");
		}
	}

	@Transactional
	public void removeCapability(AuthenticatedPrincipal principal, UUID counterId,
			UUID serviceId) {
		ServiceCounter counter = scopedCounter(principal, counterId, true);
		CounterCapability capability = capabilities
				.findByCounterIdAndServiceId(counter.getId(), serviceId)
				.orElseThrow(() -> new CapabilityNotFoundException("Capability not found"));
		capabilities.delete(capability);
	}

	private ServiceCounter scopedCounter(AuthenticatedPrincipal principal, UUID counterId,
			boolean management) {
		ServiceCounter counter = counters.findById(counterId)
				.orElseThrow(() -> new ResourceNotFoundException(
						COUNTER_CODE, "Counter not found"));
		UUID branchId = counter.getBranch().getId();
		if (principal.isOperational()) {
			if (!counter.getBranch().getOrganization().getId()
					.equals(principal.organizationId())) {
				throw new ResourceNotFoundException(COUNTER_CODE, "Counter not found");
			}
		} else if (management) {
			if (counter.getBranch().getOrganization().getStatus()
					!= com.sourabh.smartqueue.organization.OrganizationStatus.ACTIVE) {
				throw new ResourceNotFoundException(COUNTER_CODE, "Counter not found");
			}
		} else {
			if (counter.getStatus() != CounterStatus.ACTIVE) {
				throw new ResourceNotFoundException(COUNTER_CODE, "Counter not found");
			}
			CustomerScope.requireActiveBranch(counter.getBranch(), COUNTER_CODE,
					"Counter not found");
		}
		// Customers read capabilities of ACTIVE counters at ACTIVE
		// branches (display data, ADR-040); writes reach the management
		// rule, which denies customers because they hold no organization.
		if (management) {
			access.requireBranchManagement(principal, branchId);
		} else if (principal.isOperational()) {
			access.requireBranchAccess(principal, branchId);
		}
		return counter;
	}
}

