package com.sourabh.smartqueue.queue.realtime;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;

/**
 * Live wait-time freshness policy (see ADR-020) — project policy, not an
 * industry standard.
 *
 * <ul>
 *   <li>{@code enabled} — master switch for the scheduled derived-state
 *   refresh. Off disables time-driven freshness entirely; event-driven
 *   snapshots (Phase 3C bridge) keep working.</li>
 *   <li>{@code interval} — how often active queues are rebuilt while they
 *   hold live entries. Default one minute: roughly fifteen rebuilds per
 *   15-minute aging step (so rank drift is picked up promptly), well below
 *   the 5-minute snapshot TTL (a healthy scheduler renews each key about
 *   five times per TTL window), and one ranked query per active queue per
 *   minute against small operational tables.</li>
 * </ul>
 *
 * <p>Plain static configuration: changes require a restart (no refresh scope
 * in this codebase). The value is an ISO-8601 duration (e.g. {@code PT1M},
 * {@code PT30S}) because it also feeds the {@code @Scheduled} delay parser,
 * which only understands milliseconds or ISO-8601 — Boot-style suffixes
 * such as {@code 1m} bind here but would fail there. A zero or negative
 * interval would either busy-loop or never fire, so it fails fast at
 * startup via the constraint below, in the same style as
 * {@code QueueAgingProperties}.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "queue.realtime.refresh")
public class QueueRealtimeRefreshProperties {

	private boolean enabled = true;

	private Duration interval = Duration.ofMinutes(1);

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Duration getInterval() {
		return interval;
	}

	public void setInterval(Duration interval) {
		this.interval = interval;
	}

	@AssertTrue(message = "queue.realtime.refresh.interval must be a positive duration")
	public boolean isIntervalPositive() {
		return interval != null && !interval.isZero() && !interval.isNegative();
	}
}
