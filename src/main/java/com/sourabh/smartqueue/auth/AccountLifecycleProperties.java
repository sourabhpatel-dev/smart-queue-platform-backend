package com.sourabh.smartqueue.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Account lifecycle policy (see ADR-037) — project policy, not an
 * industry standard.
 *
 * <ul>
 *   <li>{@code verification-token-ttl} — how long a registration
 *   verification link works (default 24h: enough for inbox delays,
 *   short enough to bound the window).</li>
 *   <li>{@code reset-token-ttl} — how long a password-reset link
 *   works (default 1h: resets prove inbox access right now, not next
 *   week).</li>
 *   <li>{@code invitation-token-ttl} — how long an employee
 *   invitation works (default 7d: hiring moves slower than
 *   inboxes).</li>
 *   <li>{@code password-min-length} / {@code password-max-length} —
 *   8–72 chars. 72 is the BCrypt ceiling (longer inputs truncate
 *   silently, so the limit is enforced, never cut); 8 with a
 *   letter-and-digit rule is the documented minimum.</li>
 * </ul>
 *
 * <p>Plain static configuration: changes require a restart (no refresh
 * scope in this codebase). Non-positive TTLs fail fast at startup in
 * the established {@code AuthProperties} style. Overridable via
 * environment as {@code APP_ACCOUNT_*}.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.account")
public class AccountLifecycleProperties {

	private Duration verificationTokenTtl = Duration.ofHours(24);

	private Duration resetTokenTtl = Duration.ofHours(1);

	private Duration invitationTokenTtl = Duration.ofDays(7);

	@Valid
	private final Cleanup cleanup = new Cleanup();

	@Min(value = 4, message = "app.account.password-min-length must be at least 4")
	private int passwordMinLength = 8;

	@Min(value = 8, message = "app.account.password-max-length must allow the minimum")
	@Max(value = 72, message = "app.account.password-max-length must not exceed the BCrypt 72-byte ceiling")
	private int passwordMaxLength = 72;

	public Duration getVerificationTokenTtl() {
		return verificationTokenTtl;
	}

	public void setVerificationTokenTtl(Duration verificationTokenTtl) {
		this.verificationTokenTtl = verificationTokenTtl;
	}

	public Duration getResetTokenTtl() {
		return resetTokenTtl;
	}

	public void setResetTokenTtl(Duration resetTokenTtl) {
		this.resetTokenTtl = resetTokenTtl;
	}

	public Duration getInvitationTokenTtl() {
		return invitationTokenTtl;
	}

	public void setInvitationTokenTtl(Duration invitationTokenTtl) {
		this.invitationTokenTtl = invitationTokenTtl;
	}

	public int getPasswordMinLength() {
		return passwordMinLength;
	}

	public void setPasswordMinLength(int passwordMinLength) {
		this.passwordMinLength = passwordMinLength;
	}

	public int getPasswordMaxLength() {
		return passwordMaxLength;
	}

	public Cleanup getCleanup() {
		return cleanup;
	}

	/** Expired/consumed lifecycle-row sweep (same shape as notification retention). */
	public static class Cleanup {

		private boolean enabled = true;

		private Duration interval = Duration.ofHours(24);

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public Duration getInterval() {
			return interval;
		}

		public void setInterval(Duration interval) {
			this.interval = interval;
		}

		@AssertTrue(message = "app.account.cleanup.interval must be a positive duration")
		public boolean isIntervalPositive() {
			return interval != null && !interval.isZero() && !interval.isNegative();
		}
	}

	public void setPasswordMaxLength(int passwordMaxLength) {
		this.passwordMaxLength = passwordMaxLength;
	}

	@AssertTrue(message = "app.account.verification-token-ttl must be a positive duration")
	public boolean isVerificationTokenTtlPositive() {
		return verificationTokenTtl != null
				&& !verificationTokenTtl.isZero() && !verificationTokenTtl.isNegative();
	}

	@AssertTrue(message = "app.account.reset-token-ttl must be a positive duration")
	public boolean isResetTokenTtlPositive() {
		return resetTokenTtl != null
				&& !resetTokenTtl.isZero() && !resetTokenTtl.isNegative();
	}

	@AssertTrue(message = "app.account.invitation-token-ttl must be a positive duration")
	public boolean isInvitationTokenTtlPositive() {
		return invitationTokenTtl != null
				&& !invitationTokenTtl.isZero() && !invitationTokenTtl.isNegative();
	}

	@AssertTrue(message = "app.account.password-max-length must allow the minimum length")
	public boolean isPasswordRangeCoherent() {
		return passwordMaxLength >= passwordMinLength;
	}
}
