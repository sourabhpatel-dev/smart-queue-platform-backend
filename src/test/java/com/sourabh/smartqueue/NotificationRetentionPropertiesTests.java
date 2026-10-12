package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.sourabh.smartqueue.notification.NotificationRetentionProperties;

/**
 * Retention configuration validation (see ADR-033): read/unread
 * horizons and the sweep interval default sensibly, bind custom
 * ISO-8601 values, and fail fast on zero/negative durations — in the
 * same style as {@code QueueRealtimeRefreshProperties}. No containers
 * needed — only binding plus validation run here.
 */
class NotificationRetentionPropertiesTests {

	@Configuration
	@EnableConfigurationProperties(NotificationRetentionProperties.class)
	static class PropsConfiguration {
		@Bean
		LocalValidatorFactoryBean validator() {
			return new LocalValidatorFactoryBean();
		}
	}

	private final ApplicationContextRunner runner =
			new ApplicationContextRunner().withUserConfiguration(PropsConfiguration.class);

	@Test
	void defaultsAreEnabledWithNinetyAndYearAndSixHours() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			NotificationRetentionProperties properties =
					context.getBean(NotificationRetentionProperties.class);
			assertThat(properties.isEnabled()).isTrue();
			assertThat(properties.getReadAfter()).isEqualTo(Duration.ofDays(90));
			assertThat(properties.getUnreadAfter()).isEqualTo(Duration.ofDays(365));
			assertThat(properties.getInterval()).isEqualTo(Duration.ofHours(6));
		});
	}

	@Test
	void validCustomValuesBind() {
		runner.withPropertyValues(
				"notifications.retention.read-after=P30D",
				"notifications.retention.unread-after=P180D",
				"notifications.retention.interval=PT1H")
				.run(context -> {
					assertThat(context).hasNotFailed();
					NotificationRetentionProperties properties =
							context.getBean(NotificationRetentionProperties.class);
					assertThat(properties.getReadAfter()).isEqualTo(Duration.ofDays(30));
					assertThat(properties.getUnreadAfter()).isEqualTo(Duration.ofDays(180));
					assertThat(properties.getInterval()).isEqualTo(Duration.ofHours(1));
				});
	}

	@Test
	void disabledBindsWithoutValidationErrors() {
		runner.withPropertyValues("notifications.retention.enabled=false")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(NotificationRetentionProperties.class)
							.isEnabled()).isFalse();
				});
	}

	@Test
	void zeroReadAfterFailsFast() {
		runner.withPropertyValues("notifications.retention.read-after=PT0S")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void negativeUnreadAfterFailsFast() {
		runner.withPropertyValues("notifications.retention.unread-after=-P1D")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void zeroIntervalFailsFast() {
		runner.withPropertyValues("notifications.retention.interval=PT0S")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void negativeIntervalFailsFast() {
		runner.withPropertyValues("notifications.retention.interval=-PT1S")
				.run(context -> assertThat(context).hasFailed());
	}
}
