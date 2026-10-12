package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.sourabh.smartqueue.auth.AccountLifecycleProperties;

/**
 * Account lifecycle policy validation (see ADR-037): token TTLs
 * default sensibly, bind custom values, and fail fast on
 * non-positive TTLs, unknown providers, or incoherent password
 * ranges. No containers — binding plus validation only.
 */
class AccountLifecyclePropertiesTests {

	@Configuration
	@EnableConfigurationProperties(AccountLifecycleProperties.class)
	static class PropsConfiguration {
		@Bean
		LocalValidatorFactoryBean validator() {
			return new LocalValidatorFactoryBean();
		}
	}

	private final ApplicationContextRunner runner =
			new ApplicationContextRunner().withUserConfiguration(PropsConfiguration.class);

	@Test
	void defaultsAreSensible() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			AccountLifecycleProperties properties =
					context.getBean(AccountLifecycleProperties.class);
			assertThat(properties.getVerificationTokenTtl()).isEqualTo(Duration.ofHours(24));
			assertThat(properties.getResetTokenTtl()).isEqualTo(Duration.ofHours(1));
			assertThat(properties.getInvitationTokenTtl()).isEqualTo(Duration.ofDays(7));
			assertThat(properties.getPasswordMinLength()).isEqualTo(8);
			assertThat(properties.getPasswordMaxLength()).isEqualTo(72);
			assertThat(properties.getCleanup().isEnabled()).isTrue();
			assertThat(properties.getCleanup().getInterval()).isEqualTo(Duration.ofHours(24));
		});
	}

	@Test
	void customValuesBind() {
		runner.withPropertyValues(
				"app.account.verification-token-ttl=PT48H",
				"app.account.reset-token-ttl=PT30M",
				"app.account.invitation-token-ttl=P14D",
				"app.account.password-min-length=10",
				"app.account.cleanup.interval=PT12H")
				.run(context -> {
					assertThat(context).hasNotFailed();
					AccountLifecycleProperties properties =
							context.getBean(AccountLifecycleProperties.class);
					assertThat(properties.getVerificationTokenTtl()).isEqualTo(Duration.ofHours(48));
					assertThat(properties.getResetTokenTtl()).isEqualTo(Duration.ofMinutes(30));
					assertThat(properties.getInvitationTokenTtl()).isEqualTo(Duration.ofDays(14));
					assertThat(properties.getPasswordMinLength()).isEqualTo(10);
					assertThat(properties.getCleanup().getInterval()).isEqualTo(Duration.ofHours(12));
				});
	}

	@Test
	void zeroTtlFailsFast() {
		runner.withPropertyValues("app.account.reset-token-ttl=PT0S")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void negativeTtlFailsFast() {
		runner.withPropertyValues("app.account.invitation-token-ttl=-P1D")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void zeroCleanupIntervalFailsFast() {
		runner.withPropertyValues("app.account.cleanup.interval=PT0S")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void incoherentPasswordRangeFailsFast() {
		runner.withPropertyValues(
				"app.account.password-min-length=10",
				"app.account.password-max-length=9")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void overlongPasswordCeilingFailsFast() {
		runner.withPropertyValues("app.account.password-max-length=100")
				.run(context -> assertThat(context).hasFailed());
	}
}
