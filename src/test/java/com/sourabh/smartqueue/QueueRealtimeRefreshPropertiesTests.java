package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.sourabh.smartqueue.queue.realtime.QueueRealtimeRefreshProperties;

/**
 * Refresh configuration validation (see ADR-020): the interval must be a
 * positive ISO-8601 duration, failing fast at startup in the same style as
 * {@code QueueAgingProperties}. No containers needed — only binding plus
 * validation run here.
 */
class QueueRealtimeRefreshPropertiesTests {

	@Configuration
	@EnableConfigurationProperties(QueueRealtimeRefreshProperties.class)
	static class PropsConfiguration {
		@Bean
		LocalValidatorFactoryBean validator() {
			return new LocalValidatorFactoryBean();
		}
	}

	private final ApplicationContextRunner runner =
			new ApplicationContextRunner().withUserConfiguration(PropsConfiguration.class);

	@Test
	void defaultsAreEnabledWithOneMinuteInterval() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			QueueRealtimeRefreshProperties properties =
					context.getBean(QueueRealtimeRefreshProperties.class);
			assertThat(properties.isEnabled()).isTrue();
			assertThat(properties.getInterval()).isEqualTo(java.time.Duration.ofMinutes(1));
		});
	}

	@Test
	void validCustomIntervalBinds() {
		runner.withPropertyValues("queue.realtime.refresh.interval=PT30S")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(QueueRealtimeRefreshProperties.class)
							.getInterval()).isEqualTo(java.time.Duration.ofSeconds(30));
				});
	}

	@Test
	void zeroIntervalFailsFast() {
		runner.withPropertyValues("queue.realtime.refresh.interval=PT0S")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void negativeIntervalFailsFast() {
		runner.withPropertyValues("queue.realtime.refresh.interval=-PT1S")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void unparseableIntervalFailsFast() {
		runner.withPropertyValues("queue.realtime.refresh.interval=bogus")
				.run(context -> assertThat(context).hasFailed());
	}
}
