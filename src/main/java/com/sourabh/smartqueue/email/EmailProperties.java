package com.sourabh.smartqueue.email;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;

/**
 * Outbound email policy (see the email decision notes).
 *
 * <ul>
 *   <li>{@code provider} — {@code log} (default: record the
 *   delivery attempt in logs, send nothing) or {@code smtp} (real
 *   delivery through the configured relay). Anything else fails
 *   fast; {@code smtp} without a host fails fast too.</li>
 *   <li>{@code smtp.*} — relay connection, all from
 *   {@code EMAIL_SMTP_*} environment variables. No credentials are
 *   committed, ever. {@code starttls-enabled} stays {@code false} for
 *   local relays like Mailpit (plain SMTP on the loopback interface)
 *   and is set {@code true} for real external relays.</li>
 *   <li>{@code from-address} — the envelope sender users see
 *   (e.g. {@code no-reply@example.com}).</li>
 *   <li>{@code frontend-base-url} — where single-use links point
 *   (e.g. {@code https://app.example.com}); tokens ride in its
 *   query strings, so this must be the real user-facing origin.</li>
 *   <li>{@code link-path.*} — frontend routes for each link type
 *   (kept here, not hardcoded in services, so copy and routing stay
 *   in one place).</li>
 * </ul>
 *
 * <p>Static configuration: changes require a restart. Overridable
 * via environment as {@code APP_EMAIL_*}.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.email")
public class EmailProperties {

	private String provider = "log";

	private final Smtp smtp = new Smtp();

	private String fromAddress = "no-reply@smartqueue.local";

	private String frontendBaseUrl = "http://localhost:5173";

	private final LinkPath linkPath = new LinkPath();

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public Smtp getSmtp() {
		return smtp;
	}

	public String getFromAddress() {
		return fromAddress;
	}

	public void setFromAddress(String fromAddress) {
		this.fromAddress = fromAddress;
	}

	public String getFrontendBaseUrl() {
		return frontendBaseUrl;
	}

	public void setFrontendBaseUrl(String frontendBaseUrl) {
		this.frontendBaseUrl = frontendBaseUrl;
	}

	public LinkPath getLinkPath() {
		return linkPath;
	}

	public static class Smtp {

		private String host;

		@Min(value = 1, message = "app.email.smtp.port must be a valid port")
		private int port = 1025;

		private String username;

		private String password;

		private boolean starttlsEnabled = false;

		public String getHost() {
			return host;
		}

		public void setHost(String host) {
			this.host = host;
		}

		public int getPort() {
			return port;
		}

		public void setPort(int port) {
			this.port = port;
		}

		public String getUsername() {
			return username;
		}

		public void setUsername(String username) {
			this.username = username;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}

		public boolean isStarttlsEnabled() {
			return starttlsEnabled;
		}

		public void setStarttlsEnabled(boolean starttlsEnabled) {
			this.starttlsEnabled = starttlsEnabled;
		}
	}

	public static class LinkPath {

		private String verifyEmail = "/verify-email";

		private String resetPassword = "/reset-password";

		private String invitationAccept = "/invitations/accept";

		public String getVerifyEmail() {
			return verifyEmail;
		}

		public void setVerifyEmail(String verifyEmail) {
			this.verifyEmail = verifyEmail;
		}

		public String getResetPassword() {
			return resetPassword;
		}

		public void setResetPassword(String resetPassword) {
			this.resetPassword = resetPassword;
		}

		public String getInvitationAccept() {
			return invitationAccept;
		}

		public void setInvitationAccept(String invitationAccept) {
			this.invitationAccept = invitationAccept;
		}
	}

	@AssertTrue(message = "app.email.provider must be 'log' or 'smtp'")
	public boolean isProviderKnown() {
		return "log".equals(provider) || "smtp".equals(provider);
	}

	@AssertTrue(message = "app.email.smtp.host is required when app.email.provider is 'smtp'")
	public boolean isSmtpConfigured() {
		return !"smtp".equals(provider) || (smtp.getHost() != null && !smtp.getHost().isBlank());
	}
}
