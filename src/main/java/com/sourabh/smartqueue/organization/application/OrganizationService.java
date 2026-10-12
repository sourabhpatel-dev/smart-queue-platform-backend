package com.sourabh.smartqueue.organization.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.organization.api.OrganizationResponse;

/**
 * Organization reads behind the tenant boundary (see ADR-022).
 *
 * <p>Thin by rule: no business logic here beyond scope. A caller may read
 * exactly its own organization — any other id (unknown or foreign) yields
 * the same scope-safe {@code 404}, so the endpoint can never enumerate
 * tenants. Role plays no part: every authenticated role may observe its own
 * organization.
 */
@Service
public class OrganizationService {

	static final String CODE = "ORGANIZATION_NOT_FOUND";

	private final OrganizationRepository organizations;

	public OrganizationService(OrganizationRepository organizations) {
		this.organizations = organizations;
	}

	@Transactional(readOnly = true)
	public OrganizationResponse getOrganization(AuthenticatedPrincipal principal,
			UUID organizationId) {
		// Platform-level customers hold no organization: the endpoint is
		// the operational read of one's own tenant (ADR-040).
		if (!principal.hasOrganization()
				|| !principal.organizationId().equals(organizationId)) {
			throw new ResourceNotFoundException(CODE, "Organization not found");
		}
		Organization organization = organizations.findById(organizationId)
				.orElseThrow(() -> new ResourceNotFoundException(CODE,
						"Organization not found"));
		return OrganizationResponse.from(organization);
	}
}
