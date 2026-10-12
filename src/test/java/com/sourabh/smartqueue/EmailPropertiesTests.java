package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.sourabh.smartqueue.email.EmailProperties;

/**
 * Email policy validation: log-by-default, custom SMTP binding,
 * and fail-fast on unknown providers or hostless SMTP. No
 * containers — binding plus validation only. Secrets are never
 * asserted by value here (placeholders only).
 */
class EmailPropertiesTests {

	@Configuration
	@EnableConfigurationProperties(EmailProperties.class)
	static class PropsConfiguration {
		@Bean
		LocalValidatorFactoryBean validator() {
			return new LocalValidatorFactoryBean();
		}
	}

	private final ApplicationContextRunner runner =
			new ApplicationContextRunner().withUserConfiguration(PropsConfiguration.class);

	@Test
	void defaultsToLogProvider() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			EmailProperties properties = context.getBean(EmailProperties.class);
			assertThat(properties.getProvider()).isEqualTo("log");
			assertThat(properties.getSmtp().getPort()).isEqualTo(1025);
			assertThat(properties.getLinkPath().getVerifyEmail()).isEqualTo("/verify-email");
			assertThat(properties.getLinkPath().getResetPassword()).isEqualTo("/reset-password");
			assertThat(properties.getLinkPath().getInvitationAccept())
					.isEqualTo("/invitations/accept");
		});
	}

	@Test
	void smtpConfigurationBinds() {
		runner.withPropertyValues(
				"app.email.provider=smtp",
				"app.email.from-address=no-reply@example.com",
				"app.email.frontend-base-url=https://app.example.com",
				"app.email.smtp.host=mail.example.com",
				"app.email.smtp.port=587")
				.run(context -> {
					assertThat(context).hasNotFailed();
					EmailProperties properties = context.getBean(EmailProperties.class);
					assertThat(properties.getProvider()).isEqualTo("smtp");
					assertThat(properties.getSmtp().getHost()).isEqualTo("mail.example.com");
					assertThat(properties.getSmtp().getPort()).isEqualTo(587);
					// Local-relay default: plain SMTP unless explicitly enabled.
					assertThat(properties.getSmtp().isStarttlsEnabled()).isFalse();
				});
	}

	@Test
	void starttlsFlagBindsForExternalRelays() {
		runner.withPropertyValues(
				"app.email.provider=smtp",
				"app.email.smtp.host=mail.example.com",
				"app.email.smtp.starttls-enabled=true")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(EmailProperties.class)
							.getSmtp().isStarttlsEnabled()).isTrue();
				});
	}

	@Test
	void unknownProviderFailsFast() {
		runner.withPropertyValues("app.email.provider=carrier-pigeon")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void smtpWithoutHostFailsFast() {
		runner.withPropertyValues("app.email.provider=smtp")
				.run(context -> assertThat(context).hasFailed());
	}
}
