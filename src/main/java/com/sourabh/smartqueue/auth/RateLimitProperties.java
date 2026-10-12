package com.sourabh.smartqueue.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;

/**
 * Authentication abuse protection (see the rate-limit decision
 * notes): fixed-window attempt budgets for public auth endpoints.
 * Defaults are deliberately modest (a human retries rarely; a
 * script retries constantly). Static configuration, restart to
 * change, fail fast on nonsense values. Overridable via environment
 * as {@code APP_SECURITY_RATE_LIMIT_*}.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.security.rate-limit")
public class RateLimitProperties {

	private boolean enabled = true;

	private Duration window = Duration.ofMinutes(1);

	@Min(value = 1, message = "app.security.rate-limit.login-max-attempts must be at least 1")
	private int loginMaxAttempts = 10;

	@Min(value = 1, message = "app.security.rate-limit.register-max-attempts must be at least 1")
	private int registerMaxAttempts = 10;

	@Min(value = 1, message = "app.security.rate-limit.recovery-max-attempts must be at least 1")
	private int recoveryMaxAttempts = 5;

	@Min(value = 1, message = "app.security.rate-limit.resend-max-attempts must be at least 1")
	private int resendMaxAttempts = 5;

	@Min(value = 1, message = "app.security.rate-limit.invitation-accept-max-attempts must be at least 1")
	private int invitationAcceptMaxAttempts = 10;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Duration getWindow() {
		return window;
	}

	public void setWindow(Duration window) {
		this.window = window;
	}

	public int getLoginMaxAttempts() {
		return loginMaxAttempts;
	}

	public void setLoginMaxAttempts(int loginMaxAttempts) {
		this.loginMaxAttempts = loginMaxAttempts;
	}

	public int getRegisterMaxAttempts() {
		return registerMaxAttempts;
	}

	public void setRegisterMaxAttempts(int registerMaxAttempts) {
		this.registerMaxAttempts = registerMaxAttempts;
	}

	public int getRecoveryMaxAttempts() {
		return recoveryMaxAttempts;
	}

	public void setRecoveryMaxAttempts(int recoveryMaxAttempts) {
		this.recoveryMaxAttempts = recoveryMaxAttempts;
	}

	public int getResendMaxAttempts() {
		return resendMaxAttempts;
	}

	public void setResendMaxAttempts(int resendMaxAttempts) {
		this.resendMaxAttempts = resendMaxAttempts;
	}

	public int getInvitationAcceptMaxAttempts() {
		return invitationAcceptMaxAttempts;
	}

	public void setInvitationAcceptMaxAttempts(int invitationAcceptMaxAttempts) {
		this.invitationAcceptMaxAttempts = invitationAcceptMaxAttempts;
	}

	@AssertTrue(message = "app.security.rate-limit.window must be a positive duration")
	public boolean isWindowPositive() {
		return window != null && !window.isZero() && !window.isNegative();
	}

	/**
	 * Budget for a named scope (login, register, recovery, resend,
	 * verification, invitation). Verification shares the resend budget
	 * — one flow, one allowance.
	 */
	public int maxAttempts(String scope) {
		return switch (scope) {
			case RateLimiter.SCOPE_LOGIN -> loginMaxAttempts;
			case RateLimiter.SCOPE_REGISTER -> registerMaxAttempts;
			case RateLimiter.SCOPE_RECOVERY -> recoveryMaxAttempts;
			case RateLimiter.SCOPE_RESEND, RateLimiter.SCOPE_VERIFICATION -> resendMaxAttempts;
			case RateLimiter.SCOPE_INVITATION -> invitationAcceptMaxAttempts;
			default -> throw new IllegalArgumentException("unknown rate-limit scope: " + scope);
		};
	}
}
