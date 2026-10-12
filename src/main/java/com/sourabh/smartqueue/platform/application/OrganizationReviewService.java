package com.sourabh.smartqueue.platform.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.auth.AccountVerificationTokenRepository;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.membership.MembershipStatus;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.organization.OrganizationStatus;
import com.sourabh.smartqueue.platform.OrganizationTransitionException;
import com.sourabh.smartqueue.platform.PlatformAdminPolicy;
import com.sourabh.smartqueue.platform.api.PlatformApplicationResponse;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Platform-administrator review of business applications (see ADR-041).
 *
 * <p>Every method starts with {@link PlatformAdminPolicy}: the
 * configuration plus the live account state authorize the call — never
 * a client-supplied role or a stale token claim. Decisions lock the
 * organization row, move only {@code PENDING_APPROVAL → ACTIVE} or
 * {@code → REJECTED}, and activate the owner's {@code ADMIN} membership
 * in the same transaction as the approval (rejection leaves it
 * {@code INACTIVE}, granting nothing). Anything else fails with a
 * stable {@code 409}, so repeated or concurrent decisions can neither
 * fork the status nor double-provision memberships.
 */
@Service
public class OrganizationReviewService {

	static final String CODE = "ORGANIZATION_NOT_FOUND";

	private static final Logger log = LoggerFactory.getLogger(OrganizationReviewService.class);

	private final PlatformAdminPolicy admins;
	private final OrganizationRepository organizations;
	private final BranchRepository branches;
	private final OrganizationMembershipRepository memberships;
	private final UserRepository users;
	private final AccountVerificationTokenRepository verifications;
	private final Clock clock;

	public OrganizationReviewService(PlatformAdminPolicy admins,
			OrganizationRepository organizations,
			BranchRepository branches,
			OrganizationMembershipRepository memberships,
			UserRepository users,
			AccountVerificationTokenRepository verifications,
			Clock clock) {
		this.admins = admins;
		this.organizations = organizations;
		this.branches = branches;
		this.memberships = memberships;
		this.users = users;
		this.verifications = verifications;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<PlatformApplicationResponse> listApplications(
			AuthenticatedPrincipal principal, String statusFilter) {
		admins.requirePlatformAdmin(principal);
		OrganizationStatus status = parseStatus(statusFilter);
		return organizations.findByStatusOrderByCreatedAtAsc(status).stream()
				.map(this::describe)
				.toList();
	}

	@Transactional(readOnly = true)
	public PlatformApplicationResponse getApplication(
			AuthenticatedPrincipal principal, UUID organizationId) {
		admins.requirePlatformAdmin(principal);
		return describe(findOrganization(organizationId));
	}

	/**
	 * Approves an eligible, verified application: the organization and
	 * the owner's {@code ADMIN} membership become {@code ACTIVE}
	 * together, or nothing changes. Approval requires the owner's email
	 * verification (inbox ownership, not legitimacy) — verification
	 * alone never activated anything; approval alone cannot either.
	 */
	@Transactional
	public PlatformApplicationResponse approve(
			AuthenticatedPrincipal principal, UUID organizationId) {
		admins.requirePlatformAdmin(principal);
		Organization organization = findLockedOrganization(organizationId);
		OrganizationMembership owner = ownerMembership(organizationId);
		if (!ownerVerified(owner.getUser().getId())) {
			throw new OrganizationTransitionException("INVALID_TRANSITION",
					"Owner email verification is required before approval");
		}
		try {
			organization.approve(principal.userId(), clock.instant());
		} catch (IllegalStateException e) {
			throw new OrganizationTransitionException("INVALID_TRANSITION", e.getMessage());
		}
		owner.setStatus(MembershipStatus.ACTIVE);
		memberships.save(owner);
		Organization decided = organizations.save(organization);
		log.info("organization application approved: organization={} by={}",
				decided.getId(), principal.userId());
		return describe(decided);
	}

	/**
	 * Rejects a pending application with applicant-facing feedback.
	 * The owner's membership stays {@code INACTIVE}: rejected
	 * applicants administer nothing and appear nowhere public.
	 */
	@Transactional
	public PlatformApplicationResponse reject(
			AuthenticatedPrincipal principal, UUID organizationId, String reason) {
		admins.requirePlatformAdmin(principal);
		Organization organization = findLockedOrganization(organizationId);
		try {
			organization.reject(reason, principal.userId(), clock.instant());
		} catch (IllegalStateException | IllegalArgumentException e) {
			throw new OrganizationTransitionException("INVALID_TRANSITION", e.getMessage());
		}
		OrganizationMembership owner = ownerMembership(organizationId);
		owner.setStatus(MembershipStatus.INACTIVE);
		memberships.save(owner);
		Organization decided = organizations.save(organization);
		log.info("organization application rejected: organization={} by={}",
				decided.getId(), principal.userId());
		return describe(decided);
	}

	private Organization findOrganization(UUID organizationId) {
		return organizations.findById(organizationId)
				.orElseThrow(() -> new ResourceNotFoundException(CODE,
						"Organization not found"));
	}

	private Organization findLockedOrganization(UUID organizationId) {
		return organizations.findLockedById(organizationId)
				.orElseThrow(() -> new ResourceNotFoundException(CODE,
						"Organization not found"));
	}

	/**
	 * The application's owner: the single ADMIN membership. Memberships
	 * are unique per (user, organization), and applications create
	 * exactly one — anything else is a data fault, surfaced loudly.
	 */
	private OrganizationMembership ownerMembership(UUID organizationId) {
		List<OrganizationMembership> active =
				memberships.findByOrganizationIdAndStatus(organizationId, MembershipStatus.ACTIVE);
		List<OrganizationMembership> inactive =
				memberships.findByOrganizationIdAndStatus(organizationId, MembershipStatus.INACTIVE);
		List<OrganizationMembership> all = new ArrayList<>(active);
		all.addAll(inactive);
		if (all.size() != 1) {
			throw new OrganizationTransitionException("INVALID_TRANSITION",
					"Organization has no single owner application");
		}
		return all.get(0);
	}

	private boolean ownerVerified(UUID ownerUserId) {
		return verifications.findByUserId(ownerUserId)
				.map(token -> token.getUsedAt() != null)
				.orElse(false);
	}

	private PlatformApplicationResponse describe(Organization organization) {
		UUID organizationId = organization.getId();
		List<Branch> orgBranches = branches.findByOrganizationId(organizationId).stream()
				.sorted(Comparator.comparing(Branch::getCreatedAt))
				.toList();
		Branch branch = orgBranches.isEmpty() ? null : orgBranches.get(0);
		List<OrganizationMembership> active =
				memberships.findByOrganizationIdAndStatus(organizationId, MembershipStatus.ACTIVE);
		List<OrganizationMembership> inactive =
				memberships.findByOrganizationIdAndStatus(organizationId, MembershipStatus.INACTIVE);
		User owner = !active.isEmpty() ? active.get(0).getUser()
				: !inactive.isEmpty() ? inactive.get(0).getUser() : null;
		String ownerEmail = owner == null ? null : owner.getEmail();
		boolean verified = owner != null && ownerVerified(owner.getId());
		return new PlatformApplicationResponse(
				organizationId,
				organization.getName(),
				organization.getStatus().name(),
				branch == null ? null : branch.getId(),
				branch == null ? null : branch.getName(),
				ownerEmail,
				verified,
				organization.getRejectionReason(),
				organization.getDecidedAt(),
				organization.getDecidedByUserId(),
				organization.getCreatedAt());
	}

	private OrganizationStatus parseStatus(String statusFilter) {
		if (statusFilter == null || statusFilter.isBlank()) {
			return OrganizationStatus.PENDING_APPROVAL;
		}
		try {
			return OrganizationStatus.valueOf(statusFilter.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new InvalidRequestException("INVALID_STATUS",
					"Unsupported status filter: " + statusFilter
							+ ". Supported: PENDING_APPROVAL, ACTIVE, REJECTED, INACTIVE.");
		}
	}
}
