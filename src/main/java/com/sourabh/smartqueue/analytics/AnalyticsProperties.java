package com.sourabh.smartqueue.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/**
 * Operational analytics policy (see ADR-035) — project policy, not an
 * industry standard.
 *
 * <p>{@code max-range-days} bounds the reporting window a single
 * overview request may cover (default 366 days: a full year plus one
 * day so leap-year-spanning annual reports stay valid). Analytics
 * must never scan unbounded history — ranges beyond the limit are
 * rejected with a client-correctable error, never silently
 * truncated. Plain static configuration: changes require a restart.
 * Must stay positive (fail fast at startup like every other policy
 * in this codebase). Overridable as {@code ANALYTICS_MAX_RANGE_DAYS}.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "analytics")
public class AnalyticsProperties {

	@Min(value = 1, message = "analytics.max-range-days must be a positive number of days")
	private int maxRangeDays = 366;

	public int getMaxRangeDays() {
		return maxRangeDays;
	}

	public void setMaxRangeDays(int maxRangeDays) {
		this.maxRangeDays = maxRangeDays;
	}
}
