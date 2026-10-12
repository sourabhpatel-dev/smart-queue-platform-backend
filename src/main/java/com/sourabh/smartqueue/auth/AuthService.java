package com.sourabh.smartqueue.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.membership.MembershipStatus;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.platform.PlatformAdminProperties;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.user.UserStatus;

/**
 * Email + password login (see ADR-021, ADR-040).
 *
 * <p>One narrow flow: normalize the identifier with the single
 * {@code User} rule, load the account, and issue a token only for an
 * {@code ACTIVE} user whose BCrypt hash matches — anything else fails with
 * the same generic message. Unknown user, inactive account, missing
 * credential, and wrong password are deliberately indistinguishable (no
 * account enumeration), and the unknown-user path still pays for one hash
 * comparison against a dummy value so timing reveals nothing either.
 * Passwords are never logged, never compared manually, never stored.
 *
 * <p><strong>Organization resolution (ADR-040):</strong> operational users
 * (STAFF/MANAGER/ADMIN) get the organization resolved from their ACTIVE
 * {@code OrganizationMembership} rows — the legacy home-organization column
 * only breaks ties when the user holds several active memberships.
 * Customers are platform-level identities and resolve to {@code null}: no
 * membership is required, and a legacy organization value never grants
 * operational access. A user whose only memberships are INACTIVE resolves
 * to no organization and is denied organization-scoped operations.
 */
@Service
public class AuthService {

	public static final String GENERIC_FAILURE = "Invalid email or password";

	private final UserRepository users;
	private final UserCredentialRepository credentials;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final AccountVerificationTokenRepository verifications;
	private final OrganizationMembershipRepository memberships;
	private final PlatformAdminProperties platformAdmins;
	private final RateLimiter rateLimiter;
	private final Clock clock;

	public AuthService(UserRepository users,
			UserCredentialRepository credentials,
			PasswordEncoder passwordEncoder,
			JwtService jwtService,
			AccountVerificationTokenRepository verifications,
			OrganizationMembershipRepository memberships,
			PlatformAdminProperties platformAdmins,
			RateLimiter rateLimiter,
			Clock clock) {
		this.users = users;
		this.credentials = credentials;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.verifications = verifications;
		this.memberships = memberships;
		this.platformAdmins = platformAdmins;
		this.rateLimiter = rateLimiter;
		this.clock = clock;
	}

	/**
	 * @return the signed access token with its principal and expiry.
	 * @throws BadCredentialsException with a generic message on any failure.
	 * @throws EmailNotVerifiedException when the credentials are correct
	 * but a verification token is still pending (checked only after the
	 * password verifies, so verification status is never probeable).
	 */
	@Transactional(readOnly = true)
	public LoginResult login(String email, String rawPassword) {
		String normalized = User.normalizeEmail(email);
		rateLimiter.check(RateLimiter.SCOPE_LOGIN, normalized == null ? "unknown" : normalized);
		User user = normalized == null ? null : users.findByEmail(normalized).orElse(null);
		String hash = user == null ? null
				: credentials.findByUserId(user.getId()).map(UserCredential::getPasswordHash).orElse(null);
		boolean active = user != null && user.getStatus() == UserStatus.ACTIVE;
		// Dummy comparison keeps the unknown-user/inactive-user path at the
		// same cost as a real check instead of failing fast on timing.
		boolean matches = hash != null && rawPassword != null
				&& passwordEncoder.matches(rawPassword, hash);
		if (hash == null) {
			passwordEncoder.matches(
					rawPassword == null ? "" : rawPassword, PasswordCredentialService.DUMMY_HASH);
		}
		if (!active || !matches) {
			throw new BadCredentialsException(GENERIC_FAILURE);
		}
		boolean pendingVerification = verifications.findByUserId(user.getId())
				.map(token -> token.getUsedAt() == null)
				.orElse(false);
		if (pendingVerification) {
			throw new EmailNotVerifiedException(
					"Email verification is pending — check your inbox or resend the link");
		}
		java.util.EnumSet<RoleType> roles = java.util.EnumSet.noneOf(RoleType.class);
		roles.addAll(user.getRoles());
		// Platform administration is server-derived, never self-assigned:
		// the bootstrap configuration names the account, and this point
		// is reachable only after the password verified AND the email
		// verification gate above passed — a configured-but-unverified
		// account never receives the role (see ADR-041). The review
		// policy re-checks configuration + live account state per call,
		// so this claim is a UX hint, not the authorization fact.
		if (platformAdmins.isConfiguredAdmin(user.getEmail())) {
			roles.add(RoleType.PLATFORM_ADMIN);
		}
		UUID organizationId = resolveOrganization(user, roles);
		AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
				user.getId(), organizationId, user.getEmail(), roles);
		JwtService.IssuedToken issued = jwtService.issueToken(principal);
		return new LoginResult(issued.token(), principal, issued.expiresAt());
	}

	/**
	 * The operational organization for a login: only ACTIVE memberships
	 * count. The legacy home organization wins ties (it is what existing
	 * sessions and URLs were built around); otherwise a single active
	 * membership resolves; several memberships without a home match
	 * resolve to none (multi-organization selection is a later phase).
	 * Customers always resolve to {@code null}.
	 */
	private UUID resolveOrganization(User user, Set<RoleType> roles) {
		boolean operational = roles.stream().anyMatch(role ->
				role == RoleType.STAFF
						|| role == RoleType.MANAGER
						|| role == RoleType.ADMIN);
		if (!operational) {
			return null;
		}
		List<OrganizationMembership> active =
				memberships.findByUserIdAndStatus(user.getId(), MembershipStatus.ACTIVE);
		UUID homeOrganization = user.getOrganization() == null
				? null : user.getOrganization().getId();
		boolean homeActive = active.stream()
				.anyMatch(m -> m.getOrganization().getId().equals(homeOrganization));
		if (homeActive) {
			return homeOrganization;
		}
		return active.size() == 1 ? active.get(0).getOrganization().getId() : null;
	}

	/** A successful login: token, who it identifies, and when it lapses. */
	public record LoginResult(String token, AuthenticatedPrincipal principal, Instant expiresAt) {

		public UUID userId() {
			return principal.userId();
		}

		/**
		 * The resolved operational organization, or {@code null} for a
		 * platform-level customer (ADR-040).
		 */
		public UUID organizationId() {
			return principal.organizationId();
		}
	}
}