package com.sourabh.smartqueue.auth;

/**
 * Abuse protection for public authentication endpoints (see the
 * rate-limit decision notes).
 *
 * <p>Fixed-window budgets per (scope, key): login and recovery are
 * keyed by normalized email, registration and invitation acceptance
 * by client address. Exceeding the budget fails the request with
 * {@code 429} — it never fails authentication itself. The Redis
 * outage behavior is fail-OPEN (logged): rate limiting degrades,
 * authentication correctness never depends on Redis.
 */
public interface RateLimiter {

	String SCOPE_LOGIN = "login";

	String SCOPE_REGISTER = "register";

	String SCOPE_RECOVERY = "recovery";

	String SCOPE_RESEND = "resend";

	/** Verification attempts share the resend budget (same flow). */
	String SCOPE_VERIFICATION = "verification";

	String SCOPE_INVITATION = "invitation";

	/**
	 * @throws RateLimitExceededException when the scope budget for the
	 * key is exhausted within the configured window.
	 */
	void check(String scope, String key);
}
