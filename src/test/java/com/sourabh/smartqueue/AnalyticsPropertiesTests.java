package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.sourabh.smartqueue.analytics.AnalyticsProperties;

/**
 * Analytics reporting-bound validation (see ADR-035): the maximum
 * reporting range defaults to a full year plus one day, binds custom
 * values, and fails fast on non-positive limits — in the same style
 * as every other policy in this codebase. No containers needed.
 */
class AnalyticsPropertiesTests {

	@Configuration
	@EnableConfigurationProperties(AnalyticsProperties.class)
	static class PropsConfiguration {
		@Bean
		LocalValidatorFactoryBean validator() {
			return new LocalValidatorFactoryBean();
		}
	}

	private final ApplicationContextRunner runner =
			new ApplicationContextRunner().withUserConfiguration(PropsConfiguration.class);

	@Test
	void defaultIsAYearPlusOneDay() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(AnalyticsProperties.class)
					.getMaxRangeDays()).isEqualTo(366);
		});
	}

	@Test
	void customValueBinds() {
		runner.withPropertyValues("analytics.max-range-days=30")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(AnalyticsProperties.class)
							.getMaxRangeDays()).isEqualTo(30);
				});
	}

	@Test
	void zeroFailsFast() {
		runner.withPropertyValues("analytics.max-range-days=0")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void negativeFailsFast() {
		runner.withPropertyValues("analytics.max-range-days=-7")
				.run(context -> assertThat(context).hasFailed());
	}
}
