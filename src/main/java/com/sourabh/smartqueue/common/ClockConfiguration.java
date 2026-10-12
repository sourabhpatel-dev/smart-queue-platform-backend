package com.sourabh.smartqueue.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Project clock (see ADR-013).
 *
 * <p>Application services read time from this bean instead of calling
 * {@code Instant.now()} directly, so tests can pin time with a fixed clock
 * while production uses the system clock. Only code that records
 * operational facts (session start/end) needs it — audit timestamps stay
 * on the entity lifecycle callbacks.
 */
@Configuration
public class ClockConfiguration {

	@Bean
	Clock systemClock() {
		return Clock.systemUTC();
	}
}
