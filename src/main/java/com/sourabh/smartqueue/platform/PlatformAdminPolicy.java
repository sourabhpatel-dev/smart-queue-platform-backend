package com.sourabh.smartqueue.platform;

import java.util.Optional;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.auth.AccountVerificationTokenRepository;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.user.UserStatus;

/**
 * Platform-level authorization boundary (see ADR-041).
 *
 * <p>The configuration set ({@link PlatformAdminProperties}) is the
 * authority — never a client-supplied role, organization id, or request
 * field, and never the JWT role claim alone. Every review call additionally
 * re-validates the live account (must exist, must be {@code ACTIVE}, must
 * have a verified email), so stale tokens issued before a config removal,
 * deactivated accounts, and never-verified configured emails all fail
 * closed with the same {@code 403} as an ordinary user.
 *
 * <p>A platform administrator holds no organization membership and gains
 * no tenant access through this policy: application review is the entire
 * grant. Organization {@code ADMIN} stays organization-scoped and can
 * never satisfy this check.
 */
@Service
public class PlatformAdminPolicy {

	private final PlatformAdminProperties bootstrap;
	private final UserRepository users;
	private final AccountVerificationTokenRepository verifications;

	public PlatformAdminPolicy(PlatformAdminProperties bootstrap,
			UserRepository users,
			AccountVerificationTokenRepository verifications) {
		this.bootstrap = bootstrap;
		this.users = users;
		this.verifications = verifications;
	}

	/**
	 * @throws AccessDeniedException unless the principal is a configured,
	 * verified, active platform administrator right now.
	 */
	@Transactional(readOnly = true)
	public void requirePlatformAdmin(AuthenticatedPrincipal principal) {
		if (!isPlatformAdmin(principal)) {
			throw new AccessDeniedException(
					"insufficient authorization for platform administration");
		}
	}

	/**
	 * The live check: configured email, plus an existing ACTIVE account
	 * behind it whose email verification is complete (no pending token
	 * row). Read-only and safe to call on every review request.
	 */
	@Transactional(readOnly = true)
	public boolean isPlatformAdmin(AuthenticatedPrincipal principal) {
		if (principal == null || !bootstrap.isConfiguredAdmin(principal.email())) {
			return false;
		}
		Optional<User> account = principal.email() == null ? Optional.empty()
				: users.findByEmail(User.normalizeEmail(principal.email()));
		if (account.isEmpty() || account.get().getStatus() != UserStatus.ACTIVE) {
			return false;
		}
		// A live (unconsumed) verification row means the inbox is still
		// unproven; absence of any row means a non-registration
		// provisioning path (invitation/seed) whose login already passed
		// the same gate. Either way this mirrors the login rule exactly.
		return verifications.findByUserId(account.get().getId())
				.map(token -> token.getUsedAt() != null)
				.orElse(true);
	}
}
