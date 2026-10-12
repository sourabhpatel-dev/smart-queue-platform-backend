package com.sourabh.smartqueue.auth.application;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.auth.AccountEmailComposer;
import com.sourabh.smartqueue.auth.AccountLifecycleProperties;
import com.sourabh.smartqueue.auth.AccountTokenGenerator;
import com.sourabh.smartqueue.auth.AccountVerificationTokenRepository;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.auth.PasswordPolicy;
import com.sourabh.smartqueue.auth.PasswordResetToken;
import com.sourabh.smartqueue.auth.PasswordResetTokenRepository;
import com.sourabh.smartqueue.auth.RateLimiter;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.email.EmailDispatch;
import com.sourabh.smartqueue.email.EmailSender;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Password recovery and change (see ADR-037).
 *
 * <p>Recovery never reveals account existence: requesting a reset
 * for an unknown email performs no work and answers identically.
 * Accepting a reset proves inbox access, so it sets the password
 * (retiring all prior sessions through the credential funnel),
 * consumes the token, and also consumes any live verification
 * token — the inbox proof satisfies both. Change requires the
 * current password and an authenticated session.
 */
@Service
public class PasswordRecoveryService {

	private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryService.class);

	private final UserRepository users;
	private final PasswordCredentialService credentials;
	private final PasswordResetTokenRepository resets;
	private final AccountVerificationTokenRepository verifications;
	private final AccountTokenGenerator tokens;
	private final AccountLifecycleProperties policy;
	private final AccountEmailComposer emails;
	private final EmailSender sender;
	private final RateLimiter rateLimiter;
	private final Clock clock;

	public PasswordRecoveryService(UserRepository users,
			PasswordCredentialService credentials,
			PasswordResetTokenRepository resets,
			AccountVerificationTokenRepository verifications,
			AccountTokenGenerator tokens,
			AccountLifecycleProperties policy,
			AccountEmailComposer emails,
			EmailSender sender,
			RateLimiter rateLimiter,
			Clock clock) {
		this.users = users;
		this.credentials = credentials;
		this.resets = resets;
		this.verifications = verifications;
		this.tokens = tokens;
		this.policy = policy;
		this.emails = emails;
		this.sender = sender;
		this.rateLimiter = rateLimiter;
		this.clock = clock;
	}

	/**
	 * Requests a reset link. Generic by design: unknown emails,
	 * verified or not, throttled or not — the caller cannot
	 * distinguish any of it.
	 */
	@Transactional
	public void requestReset(String email, String clientIp) {
		String normalized = User.normalizeEmail(email);
		rateLimiter.check(RateLimiter.SCOPE_RECOVERY, normalized == null ? clientIp : normalized);
		User user = normalized == null ? null : users.findByEmail(normalized).orElse(null);
		if (user == null) {
			return;
		}
		resets.findByUserId(user.getId()).ifPresent(existing -> {
			// Flush before re-inserting (same per-user unique
			// constraint as verification — see RegistrationService).
			resets.delete(existing);
			resets.flush();
		});
		String rawToken = tokens.generate();
		resets.save(new PasswordResetToken(user, tokens.digest(rawToken),
				clock.instant().plus(policy.getResetTokenTtl())));
		EmailDispatch.afterCommit(sender,
				emails.resetEmail(user.getEmail(), user.getDisplayName(), rawToken));
		log.info("password reset requested: user={}", user.getId());
	}

	/**
	 * Consumes a reset token with a fresh password. Unknown, expired,
	 * and reused tokens share one generic failure.
	 */
	@Transactional
	public void resetPassword(String rawToken, String newPassword, String clientIp) {
		rateLimiter.check(RateLimiter.SCOPE_RECOVERY, clientIp);
		PasswordResetToken token = rawToken == null ? null
				: resets.findByTokenHash(tokens.digest(rawToken)).orElse(null);
		Instant now = clock.instant();
		if (token == null || !token.isLive(now)) {
			throw new InvalidRequestException("INVALID_RESET",
					"This reset link is invalid or expired");
		}
		PasswordPolicy.requireValid(newPassword, policy);
		credentials.setPassword(token.getUser().getId(), newPassword);
		token.markUsed(now);
		verifications.findByUserId(token.getUser().getId()).ifPresent(pending -> {
			if (pending.getUsedAt() == null) {
				pending.markUsed(now);
			}
		});
		log.info("password reset completed: user={}", token.getUser().getId());
	}

	/**
	 * Changes the password for an authenticated session: proves the
	 * current secret first, then sets the new one (retiring all other
	 * sessions immediately, including — by the same rule — sessions
	 * on other devices).
	 */
	@Transactional
	public void changePassword(AuthenticatedPrincipal principal, String currentPassword,
			String newPassword) {
		if (!credentials.matches(principal.userId(), currentPassword)) {
			throw new InvalidRequestException("INVALID_PASSWORD",
					"The current password is incorrect");
		}
		PasswordPolicy.requireValid(newPassword, policy);
		credentials.setPassword(principal.userId(), newPassword);
		log.info("password changed: user={}", principal.userId());
	}
}
