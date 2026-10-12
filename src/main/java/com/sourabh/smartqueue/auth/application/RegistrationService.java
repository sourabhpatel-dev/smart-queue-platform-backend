package com.sourabh.smartqueue.auth.application;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.auth.AccountEmailComposer;
import com.sourabh.smartqueue.auth.AccountLifecycleProperties;
import com.sourabh.smartqueue.auth.AccountTokenGenerator;
import com.sourabh.smartqueue.auth.AccountVerificationToken;
import com.sourabh.smartqueue.auth.AccountVerificationTokenRepository;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.auth.PasswordPolicy;
import com.sourabh.smartqueue.auth.RateLimiter;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.branch.InvalidBranchRequestException;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.MembershipStatus;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.email.EmailDispatch;
import com.sourabh.smartqueue.email.EmailSender;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.organization.OrganizationStatus;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserEmailConflictException;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.user.UserStatus;

/**
 * Public account onboarding (see ADR-040, ADR-041 and ADR-037).
 *
 * <p>Two entries, two identity shapes: <strong>customers</strong>
 * self-register as platform-level identities — no organization, no
 * membership (ADR-040) — while <strong>businesses</strong> submit an
 * organization <em>application</em> (organization + initial branch +
 * owner, ADR-041). A business application is created
 * {@code PENDING_APPROVAL} with the owner's {@code ADMIN} membership
 * {@code INACTIVE}: the applicant can verify their email and watch
 * their status, but administers nothing until platform review approves
 * (which activates both). Email verification proves inbox ownership
 * only — it never activates an organization. Every account starts
 * {@code ACTIVE} with a pending verification token; login is gated on
 * that token (see {@code AuthService}), so no second status flag exists.
 * Verification emails go out after commit only — transport trouble
 * degrades to logs, never to partial accounts.
 *
 * <p>Duplicate emails fail with the existing {@code 409
 * USER_EMAIL_CONFLICT} (registration must say "already registered"
 * to be usable — enumeration accepted here and documented; login
 * and recovery stay generic). Everything else client-correctable
 * is {@code 400}.
 */
@Service
public class RegistrationService {

	private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

	private final UserRepository users;
	private final OrganizationRepository organizations;
	private final BranchRepository branches;
	private final OrganizationMembershipRepository memberships;
	private final PasswordCredentialService credentials;
	private final AccountVerificationTokenRepository verifications;
	private final AccountTokenGenerator tokens;
	private final AccountLifecycleProperties policy;
	private final AccountEmailComposer emails;
	private final EmailSender sender;
	private final RateLimiter rateLimiter;
	private final Clock clock;

	public RegistrationService(UserRepository users,
			OrganizationRepository organizations,
			BranchRepository branches,
			OrganizationMembershipRepository memberships,
			PasswordCredentialService credentials,
			AccountVerificationTokenRepository verifications,
			AccountTokenGenerator tokens,
			AccountLifecycleProperties policy,
			AccountEmailComposer emails,
			EmailSender sender,
			RateLimiter rateLimiter,
			Clock clock) {
		this.users = users;
		this.organizations = organizations;
		this.branches = branches;
		this.memberships = memberships;
		this.credentials = credentials;
		this.verifications = verifications;
		this.tokens = tokens;
		this.policy = policy;
		this.emails = emails;
		this.sender = sender;
		this.rateLimiter = rateLimiter;
		this.clock = clock;
	}

	/** A registered customer with a verification email on its way. */
	public record CustomerRegistration(UUID userId, UUID organizationId, String email) {
	}

	/**
	 * A registered business: tenant, first branch, and owner together.
	 * The owner receives an ACTIVE organization membership alongside the
	 * legacy home-organization column (ADR-040).
	 */
	public record BusinessRegistration(UUID organizationId, UUID branchId, UUID userId) {
	}

	/**
	 * Registers a platform-level customer (see ADR-040).
	 *
	 * <p>No organization is chosen and none is assigned: the account is
	 * created with {@code organization_id = NULL} and the {@code CUSTOMER}
	 * role. The customer discovers active organizations later and selects
	 * one per action (booking/queue), which is request context — never
	 * membership. Duplicate emails still fail with the existing
	 * {@code 409 USER_EMAIL_CONFLICT}, and a verification email still
	 * gates first login.
	 */
	@Transactional
	public CustomerRegistration registerCustomer(String displayName,
			String email, String rawPassword, String clientIp) {
		rateLimiter.check(RateLimiter.SCOPE_REGISTER, clientIp);
		String normalized = normalizedEmail(email);
		String name = displayName(displayName);
		PasswordPolicy.requireValid(rawPassword, policy);
		rejectDuplicateEmail(normalized);

		User user = new User(normalized, name);
		user.grantRole(RoleType.CUSTOMER);
		users.save(user);
		credentials.setPassword(user.getId(), rawPassword);
		String rawToken = issueVerification(user);
		EmailDispatch.afterCommit(sender,
				emails.verificationEmail(normalized, name, rawToken));
		log.info("platform customer registered (no organization; verification pending)");
		return new CustomerRegistration(user.getId(), null, normalized);
	}

	/**
	 * Submits a business application atomically: organization, initial
	 * branch, and owner commit together or not at all — there is no
	 * valid intermediate state (an ownerless organization or an
	 * organizationless owner must never exist).
	 *
	 * <p>The organization is created {@code PENDING_APPROVAL} and the
	 * owner's {@code ADMIN} membership {@code INACTIVE} in the same
	 * transaction (ADR-041): submitting an application grants no
	 * operational access — the applicant cannot manage, book through,
	 * or appear in discovery for the organization until platform review
	 * approves it (which activates both rows). The application accepts
	 * any legitimate email provider (Gmail, Outlook, …): no corporate
	 * domain is required, and verification proves inbox ownership only.
	 */
	@Transactional
	public BusinessRegistration registerBusiness(String organizationName, String ownerName,
			String ownerEmail, String rawPassword, String branchName, String city,
			String country, String timeZone, String clientIp) {
		rateLimiter.check(RateLimiter.SCOPE_REGISTER, clientIp);
		String orgName = organizationName(organizationName);
		String name = displayName(ownerName);
		String normalized = normalizedEmail(ownerEmail);
		PasswordPolicy.requireValid(rawPassword, policy);
		rejectDuplicateEmail(normalized);
		BranchFields fields = branchFields(branchName, city, country, timeZone);

		Organization organization = new Organization(orgName);
		organization.setStatus(OrganizationStatus.PENDING_APPROVAL);
		organizations.save(organization);
		Branch branch = branches.save(new Branch(organization, fields.name(),
				fields.city(), fields.country(), fields.zone()));
		User owner = new User(organization, normalized, name);
		owner.grantRole(RoleType.ADMIN);
		users.save(owner);
		OrganizationMembership membership =
				new OrganizationMembership(owner, organization, MembershipRole.ADMIN);
		membership.setStatus(MembershipStatus.INACTIVE);
		memberships.save(membership);
		credentials.setPassword(owner.getId(), rawPassword);
		String rawToken = issueVerification(owner);
		EmailDispatch.afterCommit(sender,
				emails.verificationEmail(normalized, name, rawToken));
		log.info("business application submitted: organization={} branch={}",
				organization.getId(), branch.getId());
		return new BusinessRegistration(organization.getId(), branch.getId(), owner.getId());
	}

	/**
	 * Consumes a verification token. Unknown, expired, and reused
	 * tokens share one generic failure (the token space is
	 * unguessable; no signal is owed).
	 */
	@Transactional
	public void verifyEmail(String rawToken, String clientIp) {
		rateLimiter.check(RateLimiter.SCOPE_VERIFICATION, clientIp);
		AccountVerificationToken token = rawToken == null ? null
				: verifications.findByTokenHash(tokens.digest(rawToken)).orElse(null);
		Instant now = clock.instant();
		if (token == null || !token.isLive(now)) {
			throw new InvalidRequestException("INVALID_VERIFICATION",
					"This verification link is invalid or expired");
		}
		token.markUsed(now);
		log.info("email verified: user={}", token.getUser().getId());
	}

	/**
	 * Reissues the verification email. Always succeeds generically —
	 * unknown emails, verified accounts, and throttled repeats all
	 * look identical (no enumeration). Only an account with a live
	 * (unconsumed) token row gets a replacement; verified accounts
	 * receive nothing — re-locking a verified login would punish the
	 * exact users who already proved their inbox.
	 */
	@Transactional
	public void resendVerification(String email, String clientIp) {
		String normalized = User.normalizeEmail(email);
		rateLimiter.check(RateLimiter.SCOPE_RESEND, normalized == null ? clientIp : normalized);
		User user = normalized == null ? null : users.findByEmail(normalized).orElse(null);
		if (user == null) {
			return;
		}
		AccountVerificationToken existing =
				verifications.findByUserId(user.getId()).orElse(null);
		if (existing == null || existing.getUsedAt() != null) {
			return;
		}
		// Flush the delete before inserting the replacement: the
		// per-user unique constraint arbitrates, and an unflushed
		// delete would collide with the insert in the same flush.
		verifications.delete(existing);
		verifications.flush();
		String rawToken = issueVerification(user);
		EmailDispatch.afterCommit(sender, emails.verificationEmail(
				user.getEmail(), user.getDisplayName(), rawToken));
		log.info("verification reissued: user={}", user.getId());
	}

	private String issueVerification(User user) {
		String rawToken = tokens.generate();
		verifications.save(new AccountVerificationToken(user, tokens.digest(rawToken),
				clock.instant().plus(policy.getVerificationTokenTtl())));
		return rawToken;
	}

	private void rejectDuplicateEmail(String normalized) {
		if (users.findByEmail(normalized).isPresent()) {
			throw new UserEmailConflictException(
					"An account already exists for this email — sign in or reset your password");
		}
	}

	private String normalizedEmail(String email) {
		String normalized = User.normalizeEmail(email);
		if (normalized == null || normalized.isBlank() || !normalized.contains("@")) {
			throw new InvalidRequestException("INVALID_REGISTRATION",
					"A valid email address is required");
		}
		return normalized;
	}

	private String displayName(String displayName) {
		if (displayName == null || displayName.isBlank()) {
			throw new InvalidRequestException("INVALID_REGISTRATION", "A name is required");
		}
		String trimmed = displayName.trim();
		if (trimmed.length() > 150) {
			throw new InvalidRequestException("INVALID_REGISTRATION",
					"Name must be at most 150 characters");
		}
		return trimmed;
	}

	private String organizationName(String organizationName) {
		if (organizationName == null || organizationName.isBlank()) {
			throw new InvalidRequestException("INVALID_REGISTRATION",
					"An organization name is required");
		}
		String trimmed = organizationName.trim();
		if (trimmed.length() > 150) {
			throw new InvalidRequestException("INVALID_REGISTRATION",
					"Organization name must be at most 150 characters");
		}
		return trimmed;
	}

	/**
	 * Validates branch fields without persisting (the real row is
	 * built after the organization exists). Timezone abbreviations
	 * are rejected like everywhere else — IANA ids only.
	 */
	private record BranchFields(String name, String city, String country, ZoneId zone) {
	}

	private BranchFields branchFields(String name, String city, String country, String timeZone) {
		if (name == null || name.isBlank() || city == null || city.isBlank()
				|| country == null || country.isBlank()) {
			throw new InvalidRequestException("INVALID_REGISTRATION",
					"Branch name, city, and country are required");
		}
		ZoneId zone;
		try {
			zone = ZoneId.of(timeZone);
		} catch (DateTimeException | NullPointerException e) {
			throw new InvalidBranchRequestException("Invalid time zone: " + timeZone);
		}
		return new BranchFields(name.trim(), city.trim(), country.trim(), zone);
	}
}

