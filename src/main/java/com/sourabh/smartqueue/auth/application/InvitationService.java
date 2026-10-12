package com.sourabh.smartqueue.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.auth.AccountEmailComposer;
import com.sourabh.smartqueue.auth.AccountLifecycleProperties;
import com.sourabh.smartqueue.auth.AccountTokenGenerator;
import com.sourabh.smartqueue.auth.OrganizationInvitation;
import com.sourabh.smartqueue.auth.OrganizationInvitationRepository;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.auth.PasswordPolicy;
import com.sourabh.smartqueue.auth.RateLimiter;
import com.sourabh.smartqueue.auth.TenantAccessPolicy;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.api.InvitationResponse;
import com.sourabh.smartqueue.auth.api.InvitationResponse;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.common.api.ApiPage;
import com.sourabh.smartqueue.common.api.ApiPageRequest;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.email.EmailDispatch;
import com.sourabh.smartqueue.email.EmailSender;
import com.sourabh.smartqueue.staff.StaffAssignment;
import com.sourabh.smartqueue.staff.StaffAssignmentRepository;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserEmailConflictException;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.user.UserStatus;

/**
 * Employee invitation lifecycle (see ADR-037).
 *
 * <p>Invitation replaces password-sharing: an ADMIN or MANAGER names
 * an email, a role, and (for staff) a branch; the invitee proves
 * inbox access with the single-use token and sets their own
 * password. Role discipline: ADMINs may invite MANAGERs and STAFF;
 * MANAGERs may invite STAFF only — creating managers (and, always,
 * admins) above your own station is rejected, never escalated.
 * Inviting an already-registered email fails honestly (they should
 * sign in or reset, not double-register).
 *
 * <p>Acceptance is public and token-bound: the token names the
 * organization, email, role, and branch, and the invitee cannot
 * alter any of it — a token for another email is simply invalid
 * (transfer prevention). Acceptance creates the user, credential,
 * and (for staff) an ACTIVE assignment atomically, then consumes
 * the invitation. No auto-login follows: the employee signs in
 * explicitly, keeping session semantics obvious.
 */
@Service
public class InvitationService {

	static final String CODE = "INVITATION_NOT_FOUND";

	static final Map<String, String> SORTABLE = Map.of(
			"createdAt", "createdAt",
			"email", "email",
			"role", "role");

	private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

	private final OrganizationInvitationRepository invitations;
	private final UserRepository users;
	private final OrganizationRepository organizations;
	private final BranchRepository branches;
	private final StaffAssignmentRepository assignments;
	private final OrganizationMembershipRepository memberships;
	private final PasswordCredentialService credentials;
	private final TenantAccessPolicy access;
	private final AccountTokenGenerator tokens;
	private final AccountLifecycleProperties policy;
	private final AccountEmailComposer emails;
	private final EmailSender sender;
	private final RateLimiter rateLimiter;
	private final Clock clock;

	public InvitationService(OrganizationInvitationRepository invitations,
			UserRepository users,
			OrganizationRepository organizations,
			BranchRepository branches,
			StaffAssignmentRepository assignments,
			OrganizationMembershipRepository memberships,
			PasswordCredentialService credentials,
			TenantAccessPolicy access,
			AccountTokenGenerator tokens,
			AccountLifecycleProperties policy,
			AccountEmailComposer emails,
			EmailSender sender,
			RateLimiter rateLimiter,
			Clock clock) {
		this.invitations = invitations;
		this.users = users;
		this.organizations = organizations;
		this.branches = branches;
		this.assignments = assignments;
		this.memberships = memberships;
		this.credentials = credentials;
		this.access = access;
		this.tokens = tokens;
		this.policy = policy;
		this.emails = emails;
		this.sender = sender;
		this.rateLimiter = rateLimiter;
		this.clock = clock;
	}

	/** What acceptance established. The employee signs in explicitly next. */
	public record Acceptance(UUID userId, UUID organizationId) {
	}

	@Transactional
	public InvitationResponse invite(AuthenticatedPrincipal principal, String email,
			RoleType role, UUID branchId) {
		access.requireOrganizationManagement(principal);
		// The organization below is the inviter's own: verify the ACTIVE
		// membership before inviting into it (ADR-040) — the JWT claim or
		// legacy column alone never suffices.
		access.requireOrganizationAccess(principal, principal.organizationId());
		if (role == RoleType.MANAGER && !principal.hasAnyRole(RoleType.ADMIN)) {
			throw new InvalidRequestException("INVALID_INVITATION",
					"Only admins can invite managers");
		}
		if (role != RoleType.MANAGER && role != RoleType.STAFF) {
			throw new InvalidRequestException("INVALID_INVITATION",
					"Invitations support MANAGER and STAFF only");
		}
		String normalized = normalizedEmail(email);
		if (users.findByEmail(normalized).isPresent()) {
			throw new UserEmailConflictException(
					"An account already exists for this email — ask them to sign in");
		}
		UUID organizationId = principal.organizationId();
		Branch branch = null;
		if (role == RoleType.STAFF) {
			branch = scopedBranch(organizationId, branchId, true);
		} else if (branchId != null) {
			throw new InvalidRequestException("INVALID_INVITATION",
					"Managers are organization-wide; omit the branch");
		}
		revokeLive(organizationId, normalized);
		String rawToken = tokens.generate();
		OrganizationInvitation invitation = new OrganizationInvitation(
				organizationOf(organizationId), branch, normalized, role,
				tokens.digest(rawToken),
				clock.instant().plus(policy.getInvitationTokenTtl()),
				userOf(principal.userId()));
		invitations.save(invitation);
		EmailDispatch.afterCommit(sender, emails.invitationEmail(normalized,
				organizationOf(organizationId).getName(), role.name(),
				branch == null ? null : branch.getName(), rawToken));
		log.info("invitation issued: organization={} role={}", organizationId, role);
		return InvitationResponse.from(invitation, clock.instant());
	}

	@Transactional(readOnly = true)
	public ApiPage<InvitationResponse> list(
			AuthenticatedPrincipal principal, int page, int size, List<String> sorts) {
		access.requireOrganizationManagement(principal);
		access.requireOrganizationAccess(principal, principal.organizationId());
		Pageable pageable = ApiPageRequest.of(page, size, sorts, SORTABLE, "createdAt",
				Sort.Direction.DESC);
		Instant now = clock.instant();
		Page<OrganizationInvitation> result =
				invitations.findByOrganizationId(principal.organizationId(), pageable);
		return ApiPage.from(result.map(invitation -> InvitationResponse.from(invitation, now)));
	}

	@Transactional
	public void revoke(AuthenticatedPrincipal principal, UUID invitationId) {
		access.requireOrganizationManagement(principal);
		access.requireOrganizationAccess(principal, principal.organizationId());
		OrganizationInvitation invitation = scopedInvitation(principal, invitationId);
		if (invitation.getAcceptedAt() != null) {
			throw new InvalidRequestException("INVALID_INVITATION",
					"An accepted invitation cannot be revoked");
		}
		if (invitation.getRevokedAt() == null) {
			invitation.markRevoked(clock.instant());
			invitations.save(invitation);
		}
		log.info("invitation revoked: invitation={}", invitation.getId());
	}

	@Transactional
	public InvitationResponse resend(AuthenticatedPrincipal principal, UUID invitationId) {
		access.requireOrganizationManagement(principal);
		access.requireOrganizationAccess(principal, principal.organizationId());
		OrganizationInvitation invitation = scopedInvitation(principal, invitationId);
		if (invitation.getAcceptedAt() != null) {
			throw new InvalidRequestException("INVALID_INVITATION",
					"An accepted invitation cannot be resent");
		}
		revokeLive(principal.organizationId(), invitation.getEmail());
		String rawToken = tokens.generate();
		OrganizationInvitation next = new OrganizationInvitation(invitation.getOrganization(),
				invitation.getBranch(), invitation.getEmail(), invitation.getRole(),
				tokens.digest(rawToken),
				clock.instant().plus(policy.getInvitationTokenTtl()),
				userOf(principal.userId()));
		invitations.save(next);
		EmailDispatch.afterCommit(sender, emails.invitationEmail(invitation.getEmail(),
				invitation.getOrganization().getName(), invitation.getRole().name(),
				invitation.getBranch() == null ? null : invitation.getBranch().getName(),
				rawToken));
		log.info("invitation reissued: organization={}", principal.organizationId());
		return InvitationResponse.from(next, clock.instant());
	}

	/**
	 * Accepts an invitation with a fresh password. Public and
	 * token-bound: the email must match the invitation (transfer
	 * prevention), the password must satisfy policy, and an already
	 * registered email fails honestly. Creates user, credential, and
	 * staff assignment atomically, then consumes the invitation. Any
	 * other outcome shares one generic failure.
	 */
	@Transactional
	public Acceptance accept(String rawToken, String email, String displayName,
			String rawPassword, String clientIp) {
		rateLimiter.check(RateLimiter.SCOPE_INVITATION, clientIp);
		OrganizationInvitation invitation = rawToken == null ? null
				: invitations.findByTokenHash(tokens.digest(rawToken)).orElse(null);
		Instant now = clock.instant();
		if (invitation == null || !invitation.isLive(now)) {
			throw new InvalidRequestException("INVALID_INVITATION",
					"This invitation link is invalid or expired");
		}
		String normalized = normalizedEmail(email);
		if (!invitation.getEmail().equals(normalized)) {
			throw new InvalidRequestException("INVALID_INVITATION",
					"This invitation link is invalid or expired");
		}
		String name = displayName(displayName);
		PasswordPolicy.requireValid(rawPassword, policy);
		if (users.findByEmail(normalized).isPresent()) {
			throw new UserEmailConflictException(
					"An account already exists for this email — sign in or reset your password");
		}
		User user = new User(invitation.getOrganization(), normalized, name);
		user.grantRole(invitation.getRole());
		users.save(user);
		// The membership row is the authorization fact for operational
		// access (ADR-040): created atomically with the account, from the
		// server-side invitation role — never from client input.
		memberships.save(new OrganizationMembership(user, invitation.getOrganization(),
				MembershipRole.valueOf(invitation.getRole().name())));
		credentials.setPassword(user.getId(), rawPassword);
		if (invitation.getRole() == RoleType.STAFF) {
			assignments.save(new StaffAssignment(invitation.getOrganization(), user,
					invitation.getBranch()));
		}
		invitation.markAccepted(now);
		log.info("invitation accepted: organization={} role={}",
				invitation.getOrganization().getId(), invitation.getRole());
		return new Acceptance(user.getId(), invitation.getOrganization().getId());
	}

	private void revokeLive(UUID organizationId, String normalizedEmail) {
		Instant now = clock.instant();
		for (OrganizationInvitation live : invitations.findLiveByOrganizationAndEmail(
				organizationId, normalizedEmail, now)) {
			live.markRevoked(now);
			invitations.save(live);
		}
	}

	private OrganizationInvitation scopedInvitation(
			AuthenticatedPrincipal principal, UUID invitationId) {
		OrganizationInvitation invitation = invitations.findById(invitationId)
				.orElseThrow(() -> new ResourceNotFoundException(CODE, "Invitation not found"));
		if (!invitation.getOrganization().getId().equals(principal.organizationId())) {
			throw new ResourceNotFoundException(CODE, "Invitation not found");
		}
		return invitation;
	}

	private Branch scopedBranch(UUID organizationId, UUID branchId, boolean required) {
		if (branchId == null) {
			if (required) {
				throw new InvalidRequestException("INVALID_INVITATION",
						"Staff invitations require a branch");
			}
			return null;
		}
		Branch branch = branches.findById(branchId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"BRANCH_NOT_FOUND", "Branch not found"));
		if (!branch.getOrganization().getId().equals(organizationId)) {
			throw new ResourceNotFoundException("BRANCH_NOT_FOUND", "Branch not found");
		}
		return branch;
	}

	private Organization organizationOf(UUID organizationId) {
		// The principal's organization always exists (it anchors the
		// authenticated session); a missing row fails closed.
		return organizations.findById(organizationId).orElseThrow(
				() -> new ResourceNotFoundException("ORGANIZATION_NOT_FOUND",
						"Organization not found"));
	}

	private User userOf(UUID userId) {
		return users.findById(userId).orElse(null);
	}

	private String normalizedEmail(String email) {
		String normalized = User.normalizeEmail(email);
		if (normalized == null || normalized.isBlank() || !normalized.contains("@")) {
			throw new InvalidRequestException("INVALID_INVITATION",
					"A valid email address is required");
		}
		return normalized;
	}

	private String displayName(String displayName) {
		if (displayName == null || displayName.isBlank()) {
			throw new InvalidRequestException("INVALID_INVITATION", "A name is required");
		}
		String trimmed = displayName.trim();
		if (trimmed.length() > 150) {
			throw new InvalidRequestException("INVALID_INVITATION",
					"Name must be at most 150 characters");
		}
		return trimmed;
	}
}

