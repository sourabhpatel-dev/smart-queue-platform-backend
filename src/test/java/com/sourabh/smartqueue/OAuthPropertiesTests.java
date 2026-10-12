package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.sourabh.smartqueue.auth.oauth.OAuthProperties;

/**
 * Social login configuration (see the OAuth decision notes): both
 * providers disabled by default, enabled only with complete
 * material, and no live credentials anywhere in tests. No
 * containers — binding plus validation only.
 */
class OAuthPropertiesTests {

	@Configuration
	@EnableConfigurationProperties(OAuthProperties.class)
	static class PropsConfiguration {
		@Bean
		LocalValidatorFactoryBean validator() {
			return new LocalValidatorFactoryBean();
		}
	}

	private final ApplicationContextRunner runner =
			new ApplicationContextRunner().withUserConfiguration(PropsConfiguration.class);

	@Test
	void bothProvidersDisabledByDefault() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			OAuthProperties properties = context.getBean(OAuthProperties.class);
			assertThat(properties.getGoogle().isEnabled()).isFalse();
			assertThat(properties.getApple().isEnabled()).isFalse();
		});
	}

	@Test
	void googleEnablesWithIdAndSecret() {
		runner.withPropertyValues(
				"app.oauth.google.client-id=test-client",
				"app.oauth.google.client-secret=test-secret")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(OAuthProperties.class)
							.getGoogle().isEnabled()).isTrue();
					assertThat(context.getBean(OAuthProperties.class)
							.getApple().isEnabled()).isFalse();
				});
	}

	@Test
	void googleIdWithoutSecretFailsFast() {
		runner.withPropertyValues("app.oauth.google.client-id=test-client")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void partialAppleMaterialStaysDisabled() {
		runner.withPropertyValues(
				"app.oauth.apple.client-id=com.example.service",
				"app.oauth.apple.team-id=TEAM123456")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(OAuthProperties.class)
							.getApple().isEnabled()).isFalse();
				});
	}
}
