package com.sourabh.smartqueue.organization.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.auth.AccountVerificationTokenRepository;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.organization.api.ApplicationStatusResponse;

/**
 * The applicant's own application status (see ADR-041).
 *
 * <p>Ownership is derived server-side from the caller's membership row
 * (any status — pending applicants hold an INACTIVE membership, which
 * is exactly the proof they applied). A caller with no membership row
 * — customers, staff of other organizations, or anyone who never
 * applied — gets the same scope-safe {@code 404}: applications are
 * never enumerable through this endpoint.
 *
 * <p>Email verification is reported from the live token state (a
 * consumed token row means verified); it is informational here — the
 * verification endpoint itself is unchanged and still the only writer.
 */
@Service
public class OrganizationApplicationService {

	static final String CODE = "APPLICATION_NOT_FOUND";

	private final OrganizationMembershipRepository memberships;
	private final BranchRepository branches;
	private final AccountVerificationTokenRepository verifications;

	public OrganizationApplicationService(
			OrganizationMembershipRepository memberships,
			BranchRepository branches,
			AccountVerificationTokenRepository verifications) {
		this.memberships = memberships;
		this.branches = branches;
		this.verifications = verifications;
	}

	@Transactional(readOnly = true)
	public ApplicationStatusResponse myApplication(AuthenticatedPrincipal principal) {
		List<OrganizationMembership> held =
				memberships.findByUserIdAndStatus(principal.userId(),
						com.sourabh.smartqueue.membership.MembershipStatus.ACTIVE);
		OrganizationMembership application = held.isEmpty() ? null : held.get(0);
		if (application == null) {
			List<OrganizationMembership> inactive =
					memberships.findByUserIdAndStatus(principal.userId(),
							com.sourabh.smartqueue.membership.MembershipStatus.INACTIVE);
			application = inactive.isEmpty() ? null : inactive.get(0);
		}
		if (application == null) {
			throw new ResourceNotFoundException(CODE, "No business application found");
		}
		UUID organizationId = application.getOrganization().getId();
		UUID branchId = branches.findByOrganizationId(organizationId).stream()
				.findFirst()
				.map(Branch::getId)
				.orElse(null);
		boolean verified = verifications.findByUserId(principal.userId())
				.map(token -> token.getUsedAt() != null)
				.orElse(true);
		return new ApplicationStatusResponse(
				organizationId,
				application.getOrganization().getName(),
				branchId,
				application.getOrganization().getStatus().name(),
				verified,
				application.getOrganization().getRejectionReason(),
				application.getOrganization().getDecidedAt());
	}
}
