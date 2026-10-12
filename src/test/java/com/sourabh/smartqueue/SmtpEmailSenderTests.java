package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.sourabh.smartqueue.email.EmailConfiguration;
import com.sourabh.smartqueue.email.EmailDeliveryException;
import com.sourabh.smartqueue.email.EmailMessage;
import com.sourabh.smartqueue.email.EmailProperties;
import com.sourabh.smartqueue.email.EmailSender;
import com.sourabh.smartqueue.email.EmailSenderConfiguration;
import com.sourabh.smartqueue.email.LogEmailSender;
import com.sourabh.smartqueue.email.SmtpEmailSender;

/**
 * SMTP sender wiring and failure semantics (Phase 4R.3.2).
 *
 * <p>No containers: sender selection, STARTTLS propagation, and the
 * transport-failure contract are proven here. Real delivery over the
 * wire is proven by {@code SmtpMailpitDeliveryTests} against Mailpit —
 * a mocked {@link JavaMailSender} is never treated as delivery proof.
 */
class SmtpEmailSenderTests {

	@Configuration
	@EnableConfigurationProperties(EmailProperties.class)
	static class MailConfiguration {
		@Bean
		LocalValidatorFactoryBean validator() {
			return new LocalValidatorFactoryBean();
		}

		@Bean
		Clock clock() {
			return Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC);
		}
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(
					EmailConfiguration.class, EmailSenderConfiguration.class))
			.withUserConfiguration(MailConfiguration.class);

	@Test
	void logProviderSendsNothing() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(EmailSender.class)).isInstanceOf(LogEmailSender.class);
			assertThat(context.getBeansOfType(JavaMailSender.class)).isEmpty();
		});
	}

	@Test
	void smtpProviderWiresSmtpSender() {
		runner.withPropertyValues(
				"app.email.provider=smtp",
				"app.email.smtp.host=localhost",
				"app.email.smtp.port=1025")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class);
					assertThat(context.getBean(JavaMailSender.class)).isNotNull();
				});
	}

	@Test
	void starttlsFlagReachesJavaMailProperties() {
		runner.withPropertyValues(
				"app.email.provider=smtp",
				"app.email.smtp.host=mail.example.com",
				"app.email.smtp.port=587",
				"app.email.smtp.username=relay-user",
				"app.email.smtp.password=relay-secret",
				"app.email.smtp.starttls-enabled=true")
				.run(context -> {
					assertThat(context).hasNotFailed();
					JavaMailSenderImpl mailSender =
							(JavaMailSenderImpl) context.getBean(JavaMailSender.class);
					assertThat(mailSender.getJavaMailProperties())
							.containsEntry("mail.smtp.auth", "true")
							.containsEntry("mail.smtp.starttls.enable", "true");
				});
	}

	@Test
	void localRelayShapeNeedsNoAuthAndNoStarttls() {
		runner.withPropertyValues(
				"app.email.provider=smtp",
				"app.email.smtp.host=localhost",
				"app.email.smtp.port=1025")
				.run(context -> {
					assertThat(context).hasNotFailed();
					JavaMailSenderImpl mailSender =
							(JavaMailSenderImpl) context.getBean(JavaMailSender.class);
					assertThat(mailSender.getJavaMailProperties())
							.doesNotContainKey("mail.smtp.auth")
							.doesNotContainKey("mail.smtp.starttls.enable");
				});
	}

	@Test
	void smtpWithoutHostFailsFastWithClearMessage() {
		runner.withPropertyValues("app.email.provider=smtp")
				.run(context -> assertThat(context)
						.hasFailed()
						.getFailure()
						.hasStackTraceContaining("app.email.smtp.host is required"));
	}

	@Test
	void transportFailureIsObservableWithoutLeakingTheBody() {
		// A real (refused) TCP connection — no mocks: the failure must
		// surface as EmailDeliveryException carrying the recipient for
		// operations, never the body (which holds single-use tokens).
		JavaMailSenderImpl refused = new JavaMailSenderImpl();
		refused.setHost("127.0.0.1");
		refused.setPort(1);
		Properties timeouts = refused.getJavaMailProperties();
		timeouts.put("mail.smtp.connectiontimeout", "2000");
		timeouts.put("mail.smtp.timeout", "2000");

		EmailProperties properties = new EmailProperties();
		properties.setFromAddress("no-reply@smartqueue.local");
		SmtpEmailSender sender = new SmtpEmailSender(refused, properties);

		EmailMessage message = new EmailMessage(
				"asha@example.com", "Verify your SmartQueue account",
				"single-use-secret-body raw-token-abc");

		Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> sender.send(message));
		assertThat(thrown)
				.isInstanceOf(EmailDeliveryException.class)
				.hasMessageContaining("asha@example.com");
		// The body (single-use token) must not leak into the exception.
		assertThat(thrown.getMessage()).doesNotContain("raw-token-abc");
		assertThat(thrown.getCause()).isNotNull();
	}
}
