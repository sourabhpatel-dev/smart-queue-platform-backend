package com.sourabh.smartqueue.platform;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import com.sourabh.smartqueue.user.User;

/**
 * Platform-administrator bootstrap configuration (see ADR-041).
 *
 * <p>The single provisioning path for the first (and any) platform
 * administrator: {@code app.platform.admin-emails} names the account
 * emails that hold platform-level authority
 * ({@code APP_PLATFORM_ADMIN_EMAILS} in the environment, comma-separated).
 * The set is intentionally allowed to be empty — an unconfigured platform
 * fails <em>closed</em> (review endpoints deny everyone) instead of
 * failing open or silently crowning the first registered user.
 *
 * <p>Being listed here grants nothing by itself: the account must exist,
 * authenticate with its own credentials, and have a verified email before
 * the login flow attaches the {@code PLATFORM_ADMIN} role, and the review
 * policy re-checks this configuration plus the live account state on
 * every call (stale tokens and config removals fail closed).
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.platform")
public class PlatformAdminProperties {

	/**
	 * Normalized (trimmed, lower-cased) administrator emails. Binds a
	 * comma-separated string or a YAML list; blank entries are dropped.
	 */
	private Set<String> adminEmails = new LinkedHashSet<>();

	public Set<String> getAdminEmails() {
		return Collections.unmodifiableSet(adminEmails);
	}

	public void setAdminEmails(Set<String> adminEmails) {
		Set<String> normalized = new LinkedHashSet<>();
		if (adminEmails != null) {
			for (String email : adminEmails) {
				String candidate = User.normalizeEmail(email);
				if (candidate != null && !candidate.isBlank() && candidate.contains("@")) {
					normalized.add(candidate);
				}
			}
		}
		this.adminEmails = normalized;
	}

	/**
	 * True when the given email identifies a configured platform
	 * administrator. Normalizes before comparing, so case/whitespace
	 * variants of a listed address match — and unlisted addresses
	 * (including nulls and blanks) never do.
	 */
	public boolean isConfiguredAdmin(String email) {
		String normalized = User.normalizeEmail(email);
		return normalized != null && adminEmails.contains(normalized);
	}
}
