package com.sourabh.smartqueue.appointment.reminder;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;

/**
 * Upcoming-appointment reminder policy (see ADR-034) — project policy,
 * not an industry standard.
 *
 * <ul>
 *   <li>{@code enabled} — master switch for the scheduled reminder
 *   sweep. Off removes the scheduler and service beans entirely, so no
 *   tick is ever registered; booking, the notification center, and
 *   retention keep working.</li>
 *   <li>{@code before} — how far ahead of {@code appointment.startAt}
 *   the reminder becomes due ({@code reminderDueAt = startAt - before}).
 *   Default 24 hours: the customer learns about tomorrow's visit a day
 *   ahead. Compared as instants — never wall-clock arithmetic, so DST
 *   transitions cannot shift the due moment.</li>
 *   <li>{@code interval} — how often due reminders are swept. Default
 *   ten minutes: reminders are operational but not urgent — exact
 *   execution at the due timestamp is not required, and the sweep
 *   catches up anything due since the last tick.</li>
 * </ul>
 *
 * <p>Plain static configuration: changes require a restart (no refresh
 * scope in this codebase). ISO-8601 durations (e.g. {@code PT24H},
 * {@code PT10M}) because they also feed the {@code @Scheduled} delay
 * parser, which only understands milliseconds or ISO-8601. Zero or
 * negative values would either remind for already-started appointments
 * or never fire, so they fail fast at startup in the same style as
 * {@code NotificationRetentionProperties}.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "appointment.reminders")
public class AppointmentReminderProperties {

	private boolean enabled = true;

	private Duration before = Duration.ofHours(24);

	private Duration interval = Duration.ofMinutes(10);

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Duration getBefore() {
		return before;
	}

	public void setBefore(Duration before) {
		this.before = before;
	}

	public Duration getInterval() {
		return interval;
	}

	public void setInterval(Duration interval) {
		this.interval = interval;
	}

	@AssertTrue(message = "appointment.reminders.before must be a positive duration")
	public boolean isBeforePositive() {
		return before != null && !before.isZero() && !before.isNegative();
	}

	@AssertTrue(message = "appointment.reminders.interval must be a positive duration")
	public boolean isIntervalPositive() {
		return interval != null && !interval.isZero() && !interval.isNegative();
	}
}
