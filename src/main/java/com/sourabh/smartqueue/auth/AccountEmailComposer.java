package com.sourabh.smartqueue.auth;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.sourabh.smartqueue.email.EmailMessage;
import com.sourabh.smartqueue.email.EmailProperties;

/**
 * Account-lifecycle email copy in one place (see the email decision
 * notes). Plain-text bodies with a single-use link built from the
 * configured frontend origin — never hardcoded hosts. Composition
 * never logs (senders log recipient + subject only, never bodies).
 * Link lifetimes are stated honestly from the same TTLs the server
 * enforces.
 */
@Component
public class AccountEmailComposer {

	private final EmailProperties email;
	private final AccountLifecycleProperties policy;

	public AccountEmailComposer(EmailProperties email, AccountLifecycleProperties policy) {
		this.email = email;
		this.policy = policy;
	}

	public EmailMessage verificationEmail(String to, String displayName, String rawToken) {
		String link = link(email.getLinkPath().getVerifyEmail(), rawToken);
		return new EmailMessage(to, "Verify your SmartQueue account",
				"Hello " + displayName + ",\n\n"
						+ "Welcome to SmartQueue. Confirm this email address to finish activating your account:\n\n"
						+ link + "\n\n"
						+ "This link works once and expires in "
						+ human(policy.getVerificationTokenTtl()) + ". "
						+ "If you did not register, ignore this email.\n\n"
						+ "— SmartQueue");
	}

	public EmailMessage resetEmail(String to, String displayName, String rawToken) {
		String link = link(email.getLinkPath().getResetPassword(), rawToken);
		return new EmailMessage(to, "Reset your SmartQueue password",
				"Hello " + displayName + ",\n\n"
						+ "Someone requested a password reset for this address. Set a new password here:\n\n"
						+ link + "\n\n"
						+ "This link works once and expires in "
						+ human(policy.getResetTokenTtl()) + ". "
						+ "If that was not you, ignore this email — your password stays unchanged.\n\n"
						+ "— SmartQueue");
	}

	public EmailMessage invitationEmail(String to, String organizationName,
			String role, String branchName, String rawToken) {
		String link = link(email.getLinkPath().getInvitationAccept(), rawToken);
		String assignment = branchName == null
				? "as " + role
				: "as " + role + " at " + branchName;
		return new EmailMessage(to, "You are invited to join " + organizationName + " on SmartQueue",
				"Hello,\n\n"
						+ organizationName + " invited you to join them on SmartQueue "
						+ assignment + ". Accept the invitation and set your password:\n\n"
						+ link + "\n\n"
						+ "This invitation works once and expires in "
						+ human(policy.getInvitationTokenTtl()) + ". "
						+ "If you were not expecting this, ignore this email.\n\n"
						+ "— SmartQueue");
	}

	private String link(String path, String rawToken) {
		String base = email.getFrontendBaseUrl();
		String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
		return normalized + path + "?token=" + rawToken;
	}

	private String human(Duration ttl) {
		if (ttl.toHours() >= 24 && ttl.toHours() % 24 == 0) {
			long days = ttl.toHours() / 24;
			return days + (days == 1 ? " day" : " days");
		}
		if (ttl.toHours() >= 1 && ttl.toMinutes() % 60 == 0) {
			long hours = ttl.toHours();
			return hours + (hours == 1 ? " hour" : " hours");
		}
		return ttl.toMinutes() + " minutes";
	}
}
