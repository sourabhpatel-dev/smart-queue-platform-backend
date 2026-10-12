package com.sourabh.smartqueue.discovery.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.branch.BranchStatus;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.discovery.api.BranchSummary;
import com.sourabh.smartqueue.discovery.api.OrganizationSummary;
import com.sourabh.smartqueue.discovery.api.ServiceSummary;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.organization.OrganizationStatus;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.service.ServiceStatus;

/**
 * Public service directory (see the discovery decision notes).
 *
 * <p>Read-only and unauthenticated: a future customer must find
 * clinics before having an account. Every listing is active-only
 * and field-minimal — the directory exposes what registration and
 * booking need (names, places, zones, durations) and nothing that
 * identifies staff, customers, or operations. Unknown ids are plain
 * 404s; there is no cross-tenant concept on a public directory.
 */
@Service
public class DiscoveryService {

	private final OrganizationRepository organizations;
	private final BranchRepository branches;
	private final ServiceRepository services;

	public DiscoveryService(OrganizationRepository organizations,
			BranchRepository branches,
			ServiceRepository services) {
		this.organizations = organizations;
		this.branches = branches;
		this.services = services;
	}

	@Transactional(readOnly = true)
	public List<OrganizationSummary> organizations() {
		return organizations.findByStatusOrderByName(OrganizationStatus.ACTIVE).stream()
				.map(organization -> new OrganizationSummary(
						organization.getId(), organization.getName()))
				.toList();
	}

	@Transactional(readOnly = true)
	public List<BranchSummary> branches(UUID organizationId) {
		var organization = organizationId == null ? null
				: organizations.findById(organizationId).orElse(null);
		// Only ACTIVE organizations expose branches: pending and
		// rejected applications (and deactivated organizations) have
		// no public directory presence at all (ADR-041).
		if (organization == null
				|| organization.getStatus() != OrganizationStatus.ACTIVE) {
			throw new ResourceNotFoundException("ORGANIZATION_NOT_FOUND",
					"Organization not found");
		}
		return branches.findByOrganizationIdAndStatusOrderByName(
				organizationId, BranchStatus.ACTIVE).stream()
				.map(branch -> new BranchSummary(branch.getId(), branch.getName(),
						branch.getCity(), branch.getCountry(),
						branch.getTimeZone().getId()))
				.toList();
	}

	@Transactional(readOnly = true)
	public List<ServiceSummary> services(UUID branchId) {
		var branch = branchId == null ? null
				: branches.findById(branchId).orElse(null);
		// Services of inactive branches — or of branches whose
		// organization is not ACTIVE (pending/rejected/deactivated) —
		// are not publicly listed (ADR-041).
		if (branch == null || branch.getStatus() != BranchStatus.ACTIVE
				|| branch.getOrganization().getStatus() != OrganizationStatus.ACTIVE) {
			throw new ResourceNotFoundException("BRANCH_NOT_FOUND", "Branch not found");
		}
		return services.findByBranchIdAndStatusOrderByName(branchId, ServiceStatus.ACTIVE)
				.stream()
				.map(service -> new ServiceSummary(service.getId(), service.getName(),
						service.getDescription(),
						(int) service.getStandardDuration().toMinutes()))
				.toList();
	}
}
