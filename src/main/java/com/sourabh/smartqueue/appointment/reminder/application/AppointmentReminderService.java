package com.sourabh.smartqueue.appointment.reminder.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sourabh.smartqueue.appointment.Appointment;
import com.sourabh.smartqueue.appointment.AppointmentRepository;
import com.sourabh.smartqueue.appointment.AppointmentStatus;
import com.sourabh.smartqueue.appointment.reminder.AppointmentReminder;
import com.sourabh.smartqueue.appointment.reminder.AppointmentReminderProperties;
import com.sourabh.smartqueue.appointment.reminder.AppointmentReminderRepository;
import com.sourabh.smartqueue.appointment.reminder.AppointmentReminderType;
import com.sourabh.smartqueue.notification.Notification;
import com.sourabh.smartqueue.notification.NotificationReferenceType;
import com.sourabh.smartqueue.notification.NotificationRepository;
import com.sourabh.smartqueue.notification.NotificationSender;
import com.sourabh.smartqueue.notification.NotificationType;
import com.sourabh.smartqueue.user.User;

/**
 * Upcoming-appointment reminder sweep (see ADR-034).
 *
 * <p>Orchestration over the existing stores: discover a bounded batch
 * of due candidates from PostgreSQL, then process each candidate in
 * its own transaction — re-checking eligibility against the committed
 * row, inserting the durable {@link AppointmentReminder} row, and
 * persisting the in-app {@link Notification} in the same commit.
 * Realtime delivery is the existing {@link NotificationSender} echo,
 * best-effort like every other notification.
 *
 * <p>Transaction design (deliberate, not default):
 *
 * <ul>
 *   <li>Discovery runs without a surrounding business transaction —
 *   it only pages ids into memory.</li>
 *   <li>Each candidate commits in its own transaction (via
 *   {@link TransactionTemplate}, not a sweep-wide transaction), so one
 *   bad appointment can neither roll back its siblings nor hold the
 *   sweep open across the whole batch (the Phase 4E retention shape).</li>
 *   <li>Reminder row and notification row share that transaction: when
 *   persistence does not commit, nothing is recorded and the next tick
 *   retries naturally. The existing {@code NotificationService#create}
 *   is intentionally <em>not</em> reused here — its
 *   {@code REQUIRES_NEW} boundary (correct for AFTER_COMMIT listeners
 *   in Phase 4D) would split this atomic unit in two. Entity,
 *   repository, sender, DTO, and tenant-verification rules are reused;
 *   only the transaction boundary differs, and the difference is why.</li>
 * </ul>
 *
 * <p>Concurrency design: two instances may discover the same
 * appointment. Both attempt the insert; the
 * {@code uq_appointment_reminder} constraint admits exactly one. The
 * violation aborts the loser's PostgreSQL transaction, so the loser
 * cannot commit afterwards — the per-candidate boundary catches that
 * outcome and re-reads committed state to classify it: a reminder row
 * now present means another scheduler won (counted duplicate, no
 * notification created); otherwise it is a real failure for the next
 * tick. No distributed lock, no {@code synchronized}, no exactly-once
 * claim — at-least-once ticks, at-most-once rows.
 */
@Service
@ConditionalOnProperty(prefix = "appointment.reminders", name = "enabled",
		havingValue = "true", matchIfMissing = true)
public class AppointmentReminderService {

	private static final Logger log =
			LoggerFactory.getLogger(AppointmentReminderService.class);

	/**
	 * Candidates processed per sweep. Bounds memory (entities of due
	 * appointments only, never the table) without a batching framework:
	 * one bounded discovery page per tick is enough at a ten-minute
	 * cadence — anything beyond the cap waits for the next tick, still
	 * before its appointment starts. Public so tests and operators can
	 * reason about the boundary.
	 */
	public static final int SWEEP_BATCH_SIZE = 200;

	/** Active states occupy a slot — the same definition the booking exclusions use. */
	private static final List<AppointmentStatus> ACTIVE_STATUSES =
			List.of(AppointmentStatus.SCHEDULED, AppointmentStatus.CONFIRMED);

	/**
	 * Branch-local rendering of the appointment instant. Explicit
	 * {@link Locale#ENGLISH} plus the appointment branch's own
	 * {@link ZoneId}: formatting never touches the server or JVM
	 * default zone, and the zone abbreviation keeps DST rendering
	 * honest (EST vs EDT).
	 */
	private static final DateTimeFormatter LOCAL_FORMAT =
			DateTimeFormatter.ofPattern("EEE, d MMM yyyy, h:mm a (z)", Locale.ENGLISH);

	private final AppointmentReminderProperties policy;
	private final AppointmentRepository appointments;
	private final AppointmentReminderRepository reminders;
	private final NotificationRepository notifications;
	private final NotificationSender sender;
	private final Clock clock;
	private final TransactionTemplate transactions;

	public AppointmentReminderService(AppointmentReminderProperties policy,
			AppointmentRepository appointments,
			AppointmentReminderRepository reminders,
			NotificationRepository notifications,
			NotificationSender sender,
			Clock clock,
			PlatformTransactionManager transactionManager) {
		this.policy = policy;
		this.appointments = appointments;
		this.reminders = reminders;
		this.notifications = notifications;
		this.sender = sender;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/** One sweep's accounting. No notification contents, no customer data. */
	public record SweepSummary(
			Instant now,
			Instant horizon,
			int considered,
			int created,
			int skipped,
			int duplicates,
			int failures) {
	}

	/**
	 * Processes every due candidate in the current bounded page. One
	 * candidate's failure never stops the remaining ones. A discovery
	 * failure (the database itself unreachable) still propagates —
	 * the scheduled tick contains it and the next tick retries.
	 */
	public SweepSummary sweepOnce() {
		Instant now = clock.instant();
		Instant horizon = now.plus(policy.getBefore());
		List<Appointment> candidates = transactions.execute(status -> appointments
				.findReminderCandidates(ACTIVE_STATUSES, now, horizon,
						AppointmentReminderType.UPCOMING,
						PageRequest.of(0, SWEEP_BATCH_SIZE)));
		if (candidates == null || candidates.isEmpty()) {
			return new SweepSummary(now, horizon, 0, 0, 0, 0, 0);
		}
		int created = 0;
		int skipped = 0;
		int duplicates = 0;
		int failures = 0;
		for (Appointment candidate : candidates) {
			Outcome outcome;
			Notification delivered = null;
			try {
				CandidateResult result = transactions.execute(tx ->
						processCandidate(candidate.getId(), now, horizon));
				outcome = result == null ? Outcome.FAILED : result.outcome();
				if (result != null) {
					delivered = result.notification();
				}
			} catch (RuntimeException e) {
				outcome = classifyConflict(candidate.getId(), e);
			}
			if (outcome == Outcome.CREATED && delivered != null) {
				sendBestEffort(delivered);
			}
			if (outcome == null || outcome == Outcome.FAILED) {
				failures++;
			} else {
				switch (outcome) {
					case CREATED -> created++;
					case SKIPPED -> skipped++;
					case DUPLICATE -> duplicates++;
					case FAILED -> failures++;
				}
			}
		}
		return new SweepSummary(now, horizon, candidates.size(),
				created, skipped, duplicates, failures);
	}

	/**
	 * Classifies a candidate transaction that did not commit.
	 *
	 * <p>The common case is a lost concurrent-claim race: the
	 * {@code uq_appointment_reminder} violation aborts the loser's
	 * PostgreSQL transaction, so even the caught flush failure cannot
	 * commit afterwards (the commit itself reports the rollback).
	 * Re-reading committed state disambiguates precisely — a reminder
	 * row now present means another scheduler won (expected duplicate,
	 * not a failure); anything else is a real failure the next tick
	 * retries. The classification read itself is guarded: when even
	 * that fails, the candidate counts as failed and its siblings
	 * still continue.
	 */
	private Outcome classifyConflict(UUID appointmentId, RuntimeException e) {
		try {
			Boolean claimed = transactions.execute(tx -> reminders
					.existsByAppointmentIdAndReminderType(
							appointmentId, AppointmentReminderType.UPCOMING));
			if (Boolean.TRUE.equals(claimed)) {
				log.info("appointment reminder for appointment {} was claimed"
						+ " by a concurrent scheduler; keeping the existing row",
						appointmentId);
				return Outcome.DUPLICATE;
			}
		} catch (RuntimeException readFailure) {
			log.warn("appointment reminder classification failed for appointment {}",
					appointmentId, readFailure);
			return Outcome.FAILED;
		}
		log.warn("appointment reminder processing failed for appointment {}",
				appointmentId, e);
		return Outcome.FAILED;
	}

	private enum Outcome {
		CREATED,
		SKIPPED,
		DUPLICATE,
		FAILED
	}

	/**
	 * One candidate's persistence outcome plus, when created, the row
	 * to echo after the transaction commits. The echo deliberately
	 * travels outside the transaction (see {@link #sendBestEffort}) —
	 * the atomic unit holds persistence only.
	 */
	private record CandidateResult(Outcome outcome, Notification notification) {
	}

	/**
	 * Sends the realtime echo for an already-committed reminder.
	 * Best-effort only: the committed rows are the reminder, the
	 * center re-reads them, and nothing here may fail the candidate.
	 * Runs strictly after commit so a client can never receive a
	 * reminder the center cannot re-read.
	 */
	private void sendBestEffort(Notification notification) {
		try {
			sender.send(notification);
		} catch (RuntimeException e) {
			log.warn("realtime appointment reminder delivery failed for notification {}",
					notification.getId(), e);
		}
	}

	/**
	 * One candidate, one transaction. Eligibility is finalized here
	 * against the freshly loaded row — a cancellation or completion
	 * that committed after discovery (or after the scheduler first saw
	 * the appointment) is visible now, and the reminder is skipped.
	 * The reverse race (cancellation committing just after this
	 * transaction) can still deliver a reminder for a then-cancelled
	 * appointment; that window is inherent without distributed locking
	 * and is documented, not hidden (see ADR-034).
	 */
	private CandidateResult processCandidate(UUID appointmentId, Instant now, Instant horizon) {
		Appointment appointment = appointments.findById(appointmentId).orElse(null);
		if (appointment == null) {
			return new CandidateResult(Outcome.SKIPPED, null);
		}
		if (appointment.getStatus() != AppointmentStatus.SCHEDULED
				&& appointment.getStatus() != AppointmentStatus.CONFIRMED) {
			return new CandidateResult(Outcome.SKIPPED, null);
		}
		if (!appointment.getStartAt().isAfter(now)
				|| appointment.getStartAt().isAfter(horizon)) {
			return new CandidateResult(Outcome.SKIPPED, null);
		}
		User customer = appointment.getCustomer();
		if (customer == null
				|| !customer.getOrganization().getId()
						.equals(appointment.getOrganization().getId())) {
			// Defensive only: the booking constructor plus the V6
			// composite foreign keys already pin the customer to the
			// appointment organization on every path. Never guessed,
			// never crossed — skip rather than notify wrongly.
			log.warn("skipping appointment reminder for appointment {}: unresolvable recipient scope",
					appointmentId);
			return new CandidateResult(Outcome.SKIPPED, null);
		}
		AppointmentReminder reminder = new AppointmentReminder(
				appointment.getOrganization(), appointment,
				AppointmentReminderType.UPCOMING, clock.instant());
		try {
			reminders.saveAndFlush(reminder);
		} catch (DataIntegrityViolationException e) {
			// Lost the concurrent-claim race on
			// uq_appointment_reminder: the other scheduler won and its
			// row (plus its notification, same transaction) is the
			// reminder. No retry, no second notification.
			log.info("appointment reminder for appointment {} raced another scheduler; keeping the existing row",
					appointmentId);
			return new CandidateResult(Outcome.DUPLICATE, null);
		}
		Notification notification = new Notification(
				appointment.getOrganization(), customer,
				NotificationType.APPOINTMENT_REMINDER,
				"Appointment reminder",
				messageFor(appointment),
				NotificationReferenceType.APPOINTMENT,
				appointment.getId(),
				reminder.getId());
		try {
			notifications.saveAndFlush(notification);
		} catch (RuntimeException e) {
			// Same-transaction persistence: the reminder row rolls back
			// with the notification, so the next tick retries cleanly.
			// Re-throw into the per-candidate guard below (counted as a
			// failure, siblings continue).
			log.warn("appointment reminder notification persistence failed for appointment {}",
					appointmentId, e);
			throw e;
		}
		return new CandidateResult(Outcome.CREATED, notification);
	}

	/**
	 * Renders the customer-facing copy from authoritative appointment
	 * data. Service and branch are display names; the instant is shown
	 * in the branch's own time zone. No ids, no tokens, no internal
	 * state — the backend owns this copy (never React). Public so
	 * timezone/DST rendering is unit-testable without a database.
	 */
	public static String messageFor(Appointment appointment) {
		ZoneId zone = appointment.getBranch().getTimeZone();
		ZonedDateTime local = appointment.getStartAt().atZone(zone);
		return "Your appointment for " + appointment.getService().getName()
				+ " at " + appointment.getBranch().getName()
				+ " is on " + local.format(LOCAL_FORMAT) + ".";
	}
}
