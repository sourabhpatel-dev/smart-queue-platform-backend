package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.sourabh.smartqueue.auth.AuthProperties;

/**
 * Authentication configuration validation (see ADR-021): the signing
 * secret is required and HS256-strong, the expiration is positive —
 * anything else fails fast at startup. No containers needed.
 */
class AuthPropertiesTests {

	@Configuration
	@EnableConfigurationProperties(AuthProperties.class)
	static class PropsConfiguration {
		@Bean
		LocalValidatorFactoryBean validator() {
			return new LocalValidatorFactoryBean();
		}
	}

	private final ApplicationContextRunner runner =
			new ApplicationContextRunner().withUserConfiguration(PropsConfiguration.class);

	@Test
	void missingSecretFailsFast() {
		runner.withPropertyValues("security.jwt.secret=")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void shortSecretFailsFast() {
		runner.withPropertyValues("security.jwt.secret=too-short")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void thirtyTwoByteSecretBinds() {
		runner.withPropertyValues("security.jwt.secret=0123456789abcdef0123456789abcdef")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(AuthProperties.class).isSecretStrongEnough()).isTrue();
				});
	}

	@Test
	void defaultExpirationIsOneHour() {
		runner.withPropertyValues("security.jwt.secret=0123456789abcdef0123456789abcdef")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(AuthProperties.class).getExpiration())
							.isEqualTo(java.time.Duration.ofHours(1));
				});
	}

	@Test
	void zeroExpirationFailsFast() {
		runner.withPropertyValues(
						"security.jwt.secret=0123456789abcdef0123456789abcdef",
						"security.jwt.expiration=PT0S")
				.run(context -> assertThat(context).hasFailed());
	}
}
