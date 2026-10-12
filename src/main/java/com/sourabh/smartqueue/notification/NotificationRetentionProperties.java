package com.sourabh.smartqueue.notification;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;

/**
 * In-app notification retention policy (see ADR-033) — project policy,
 * not an industry standard.
 *
 * <ul>
 *   <li>{@code enabled} — master switch for the scheduled cleanup. Off
 *   removes the retention bean entirely, so no tick is ever registered;
 *   notification creation, reads, and realtime delivery keep working.</li>
 *   <li>{@code readAfter} — a read notification is eligible for deletion
 *   once {@code createdAt} is older than this. Default 90 days: read rows
 *   have served their purpose and the user has seen them.</li>
 *   <li>{@code unreadAfter} — an unread notification is eligible once
 *   {@code createdAt} is older than this. Default 365 days: unread rows
 *   stay far longer so a lapsed user still finds them, but the table
 *   cannot grow without bound.</li>
 *   <li>{@code interval} — how often eligibility is swept. Default six
 *   hours: retention is janitorial, not operational, so it runs rarely
 *   and cheaply.</li>
 * </ul>
 *
 * <p>Plain static configuration: changes require a restart (no refresh
 * scope in this codebase). ISO-8601 durations (e.g. {@code P90D},
 * {@code PT6H}) because they also feed the {@code @Scheduled} delay
 * parser, which only understands milliseconds or ISO-8601. Zero or
 * negative values would either delete everything immediately or never
 * fire, so they fail fast at startup in the same style as
 * {@code QueueRealtimeRefreshProperties}.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "notifications.retention")
public class NotificationRetentionProperties {

	private boolean enabled = true;

	private Duration readAfter = Duration.ofDays(90);

	private Duration unreadAfter = Duration.ofDays(365);

	private Duration interval = Duration.ofHours(6);

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Duration getReadAfter() {
		return readAfter;
	}

	public void setReadAfter(Duration readAfter) {
		this.readAfter = readAfter;
	}

	public Duration getUnreadAfter() {
		return unreadAfter;
	}

	public void setUnreadAfter(Duration unreadAfter) {
		this.unreadAfter = unreadAfter;
	}

	public Duration getInterval() {
		return interval;
	}

	public void setInterval(Duration interval) {
		this.interval = interval;
	}

	@AssertTrue(message = "notifications.retention.read-after must be a positive duration")
	public boolean isReadAfterPositive() {
		return readAfter != null && !readAfter.isZero() && !readAfter.isNegative();
	}

	@AssertTrue(message = "notifications.retention.unread-after must be a positive duration")
	public boolean isUnreadAfterPositive() {
		return unreadAfter != null && !unreadAfter.isZero() && !unreadAfter.isNegative();
	}

	@AssertTrue(message = "notifications.retention.interval must be a positive duration")
	public boolean isIntervalPositive() {
		return interval != null && !interval.isZero() && !interval.isNegative();
	}
}
