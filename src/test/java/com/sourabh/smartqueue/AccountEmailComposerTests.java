package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sourabh.smartqueue.auth.AccountEmailComposer;
import com.sourabh.smartqueue.auth.AccountLifecycleProperties;
import com.sourabh.smartqueue.email.EmailMessage;
import com.sourabh.smartqueue.email.EmailProperties;

/**
 * Account-lifecycle email copy and link construction (Phase 4R.3.2).
 *
 * <p>No containers: the composer is pure string building over
 * {@link EmailProperties} (link origin + route paths) and
 * {@link AccountLifecycleProperties} (honest expiry wording). The
 * live SMTP proof is {@code SmtpMailpitDeliveryTests}; mocked-sender
 * tests elsewhere must never be mistaken for delivery evidence.
 */
class AccountEmailComposerTests {

	private EmailProperties email;
	private AccountLifecycleProperties policy;
	private AccountEmailComposer composer;

	@BeforeEach
	void setUp() {
		email = new EmailProperties();
		email.setFromAddress("no-reply@smartqueue.local");
		email.setFrontendBaseUrl("http://localhost:5173");
		policy = new AccountLifecycleProperties();
		composer = new AccountEmailComposer(email, policy);
	}

	@Test
	void verificationEmailLinksToFrontendVerifyRouteWithToken() {
		EmailMessage message = composer.verificationEmail(
				"asha@example.com", "Asha", "raw-token-123");

		assertThat(message.to()).isEqualTo("asha@example.com");
		assertThat(message.subject()).isEqualTo("Verify your SmartQueue account");
		assertThat(message.bodyText())
				.contains("http://localhost:5173/verify-email?token=raw-token-123");
	}

	@Test
	void verificationEmailStatesHonestExpiryAndIgnoreInstructions() {
		// Default verification TTL is P1D (see application.yml): the copy
		// must say "1 day" — the same lifetime the server enforces.
		assertThat(policy.getVerificationTokenTtl()).isEqualTo(Duration.ofDays(1));

		EmailMessage message = composer.verificationEmail(
				"asha@example.com", "Asha", "raw-token-123");

		assertThat(message.bodyText())
				.contains("expires in 1 day")
				.contains("If you did not register, ignore this email");
	}

	@Test
	void verificationEmailCarriesNoPasswordOrUnrelatedPersonalData() {
		EmailMessage message = composer.verificationEmail(
				"asha@example.com", "Asha", "raw-token-123");

		assertThat(message.bodyText()).doesNotContain("s3cure-pass");
		// The only personal datum in the message is the name used as a salutation.
		assertThat(message.bodyText()).contains("Hello Asha,");
	}

	@Test
	void trailingSlashBaseUrlBuildsOneCleanLink() {
		email.setFrontendBaseUrl("http://localhost:5173/");

		EmailMessage message = composer.verificationEmail(
				"asha@example.com", "Asha", "raw-token-123");

		assertThat(message.bodyText())
				.contains("http://localhost:5173/verify-email?token=raw-token-123");
		assertThat(message.bodyText()).doesNotContain("//verify-email");
	}

	@Test
	void linksNeverComeFromRequestHosts() {
		// The composer has no request input at all: the origin is the
		// configured frontend base URL, so a forged Host header cannot
		// redirect the emailed link. Productions sets its real origin here.
		email.setFrontendBaseUrl("https://app.example.com");

		EmailMessage message = composer.verificationEmail(
				"asha@example.com", "Asha", "raw-token-123");

		assertThat(message.bodyText())
				.startsWith("Hello Asha,")
				.contains("https://app.example.com/verify-email?token=raw-token-123");
	}

	@Test
	void resetEmailLinksToFrontendResetRouteWithMatchingExpiry() {
		// Default reset TTL is PT1H: the copy must say "1 hour".
		assertThat(policy.getResetTokenTtl()).isEqualTo(Duration.ofHours(1));

		EmailMessage message = composer.resetEmail(
				"asha@example.com", "Asha", "reset-token-456");

		assertThat(message.subject()).isEqualTo("Reset your SmartQueue password");
		assertThat(message.bodyText())
				.contains("http://localhost:5173/reset-password?token=reset-token-456")
				.contains("expires in 1 hour")
				.contains("If that was not you, ignore this email");
	}

	@Test
	void invitationEmailLinksToFrontendAcceptRouteWithMatchingExpiry() {
		assertThat(policy.getInvitationTokenTtl()).isEqualTo(Duration.ofDays(7));

		EmailMessage message = composer.invitationEmail(
				"sam@example.com", "Harbor Clinic", "STAFF", "Downtown", "invite-token-789");

		assertThat(message.subject()).contains("Harbor Clinic");
		assertThat(message.bodyText())
				.contains("http://localhost:5173/invitations/accept?token=invite-token-789")
				.contains("expires in 7 days")
				.contains("as STAFF at Downtown");
	}
}
