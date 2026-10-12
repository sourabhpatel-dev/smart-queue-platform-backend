package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.sourabh.smartqueue.appointment.reminder.AppointmentReminderProperties;

/**
 * Reminder policy configuration validation (see ADR-034): the lead
 * time and sweep interval default sensibly, bind custom ISO-8601
 * values, and fail fast on zero/negative durations — in the same
 * style as {@code NotificationRetentionProperties}. No containers
 * needed — only binding plus validation run here.
 */
class AppointmentReminderPropertiesTests {

	@Configuration
	@EnableConfigurationProperties(AppointmentReminderProperties.class)
	static class PropsConfiguration {
		@Bean
		LocalValidatorFactoryBean validator() {
			return new LocalValidatorFactoryBean();
		}
	}

	private final ApplicationContextRunner runner =
			new ApplicationContextRunner().withUserConfiguration(PropsConfiguration.class);

	@Test
	void defaultsAreEnabledWithTwentyFourHoursAndTenMinutes() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			AppointmentReminderProperties properties =
					context.getBean(AppointmentReminderProperties.class);
			assertThat(properties.isEnabled()).isTrue();
			assertThat(properties.getBefore()).isEqualTo(Duration.ofHours(24));
			assertThat(properties.getInterval()).isEqualTo(Duration.ofMinutes(10));
		});
	}

	@Test
	void validCustomValuesBind() {
		runner.withPropertyValues(
				"appointment.reminders.before=PT12H",
				"appointment.reminders.interval=PT5M")
				.run(context -> {
					assertThat(context).hasNotFailed();
					AppointmentReminderProperties properties =
							context.getBean(AppointmentReminderProperties.class);
					assertThat(properties.getBefore()).isEqualTo(Duration.ofHours(12));
					assertThat(properties.getInterval()).isEqualTo(Duration.ofMinutes(5));
				});
	}

	@Test
	void disabledBindsWithoutValidationErrors() {
		runner.withPropertyValues("appointment.reminders.enabled=false")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(AppointmentReminderProperties.class)
							.isEnabled()).isFalse();
				});
	}

	@Test
	void zeroBeforeFailsFast() {
		runner.withPropertyValues("appointment.reminders.before=PT0S")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void negativeBeforeFailsFast() {
		runner.withPropertyValues("appointment.reminders.before=-PT1H")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void zeroIntervalFailsFast() {
		runner.withPropertyValues("appointment.reminders.interval=PT0S")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void negativeIntervalFailsFast() {
		runner.withPropertyValues("appointment.reminders.interval=-PT1M")
				.run(context -> assertThat(context).hasFailed());
	}
}
