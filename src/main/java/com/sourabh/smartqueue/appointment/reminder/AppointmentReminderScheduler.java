package com.sourabh.smartqueue.appointment.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.sourabh.smartqueue.appointment.reminder.application.AppointmentReminderService;

/**
 * Thin scheduled entry point for upcoming-appointment reminders (see
 * ADR-034).
 *
 * <p>Timing only, never business logic: each tick delegates to
 * {@link AppointmentReminderService#sweepOnce} and logs the summary
 * (instants and counts — never notification contents or customer
 * data). Fixed delay (not rate) so a slow pass never overlaps itself
 * on this instance. Two instances may sweep concurrently — accepted
 * and documented: the database constraint arbitrates every shared
 * appointment, so no distributed lock is introduced for operational
 * reminders.
 *
 * <p>{@code @EnableScheduling} is already registered once for the
 * application (queue realtime freshness); scheduling needs no second
 * framework, no threads, no Quartz, no ShedLock.
 */
@Service
@ConditionalOnProperty(prefix = "appointment.reminders", name = "enabled",
		havingValue = "true", matchIfMissing = true)
public class AppointmentReminderScheduler {

	private static final Logger log =
			LoggerFactory.getLogger(AppointmentReminderScheduler.class);

	private final AppointmentReminderService reminders;

	public AppointmentReminderScheduler(AppointmentReminderService reminders) {
		this.reminders = reminders;
	}

	/**
	 * Periodic tick. Never throws outward — a failed tick logs and the
	 * next one retries (catch-up included: anything due since the last
	 * tick is still eligible while its appointment stays upcoming).
	 */
	@Scheduled(fixedDelayString = "${appointment.reminders.interval:PT10M}")
	public void scheduledReminders() {
		try {
			AppointmentReminderService.SweepSummary summary = reminders.sweepOnce();
			log.info("appointment reminder sweep: now={} horizon={} considered={} created={} skipped={} duplicates={} failures={}",
					summary.now(), summary.horizon(), summary.considered(),
					summary.created(), summary.skipped(), summary.duplicates(),
					summary.failures());
		} catch (RuntimeException e) {
			log.warn("scheduled appointment reminder sweep failed; next tick retries", e);
		}
	}
}
