package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.appointment.Appointment;
import com.sourabh.smartqueue.appointment.AppointmentRepository;
import com.sourabh.smartqueue.appointment.AppointmentStatus;
import com.sourabh.smartqueue.appointment.reminder.AppointmentReminder;
import com.sourabh.smartqueue.appointment.reminder.AppointmentReminderProperties;
import com.sourabh.smartqueue.appointment.reminder.AppointmentReminderRepository;
import com.sourabh.smartqueue.appointment.reminder.AppointmentReminderScheduler;
import com.sourabh.smartqueue.appointment.reminder.AppointmentReminderType;
import com.sourabh.smartqueue.appointment.reminder.application.AppointmentReminderService;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.notification.Notification;
import com.sourabh.smartqueue.notification.NotificationReferenceType;
import com.sourabh.smartqueue.notification.NotificationRepository;
import com.sourabh.smartqueue.notification.NotificationSender;
import com.sourabh.smartqueue.notification.NotificationType;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Upcoming-appointment reminder behavior (see ADR-034): eligibility
 * on stored instants, sequential idempotency, bounded batches,
 * failure containment, tenant safety, and branch-local rendering.
 *
 * <p>Time is pinned: a fixed project Clock defines "now" and
 * appointments are constructed with explicit instants (no
 * {@code Instant.now()} anywhere). Every test rolls back. Concurrent
 * claiming is proven separately in
 * {@link AppointmentReminderConcurrencyTests} (threads need real
 * commits, which a rollback test cannot provide).
 */
@Testcontainers
@SpringBootTest
@Transactional
class AppointmentReminderTests {

	static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@TestConfiguration
	static class FixedClockConfiguration {
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	@Autowired
	OrganizationRepository organizations;
	@Autowired
	BranchRepository branches;
	@Autowired
	ServiceRepository services;
	@Autowired
	UserRepository users;
	@Autowired
	AppointmentRepository appointments;
	@Autowired
	AppointmentReminderRepository reminders;
	@Autowired
	NotificationRepository notifications;
	@Autowired
	AppointmentReminderService reminderService;
	@Autowired
	AppointmentReminderProperties policy;
	@Autowired
	NotificationSender sender;
	@Autowired
	PlatformTransactionManager transactionManager;
	@Autowired
	Clock clock;

	Organization org;
	Branch branch;
	com.sourabh.smartqueue.service.Service consultation;
	User customer;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		org = organizations.save(new Organization("Reminder Org " + tag));
		branch = branches.save(new Branch(org, "Downtown " + tag, "Bhopal", "India", KOLKATA));
		consultation = services.save(new com.sourabh.smartqueue.service.Service(
				branch, "Consultation " + tag, Duration.ofMinutes(30)));
		customer = users.save(new User(org, "rem-" + tag + "@example.com", "Rem " + tag));
		customer.grantRole(RoleType.CUSTOMER);
		customer = users.save(customer);
		assertThat(clock.instant()).isEqualTo(NOW);
	}

	private Appointment appointment(Instant start) {
		return appointments.saveAndFlush(new Appointment(org, branch, consultation,
				customer, start, start.plus(Duration.ofMinutes(30))));
	}

	private List<Notification> notificationsFor(User recipient) {
		return notifications.findByRecipientUserId(recipient.getId(),
				Pageable.unpaged()).getContent();
	}

	@Test
	void dueAppointmentCreatesOneReminderAndOneCustomerNotification() {
		Appointment due = appointment(NOW.plus(Duration.ofHours(23)));

		AppointmentReminderService.SweepSummary summary = reminderService.sweepOnce();

		assertThat(summary.considered()).isEqualTo(1);
		assertThat(summary.created()).isEqualTo(1);
		assertThat(summary.failures()).isEqualTo(0);

		Optional<AppointmentReminder> reminder = reminders
				.findByAppointmentIdAndReminderType(due.getId(), AppointmentReminderType.UPCOMING);
		assertThat(reminder).isPresent();
		assertThat(reminder.get().getOrganization().getId()).isEqualTo(org.getId());
		assertThat(reminder.get().getSentAt()).isEqualTo(NOW);

		List<Notification> rows = notificationsFor(customer);
		assertThat(rows).hasSize(1);
		Notification row = rows.get(0);
		assertThat(row.getType()).isEqualTo(NotificationType.APPOINTMENT_REMINDER);
		assertThat(row.getTitle()).isEqualTo("Appointment reminder");
		assertThat(row.getMessage()).contains(consultation.getName());
		assertThat(row.getMessage()).contains(branch.getName());
		assertThat(row.getReferenceType()).isEqualTo(NotificationReferenceType.APPOINTMENT);
		assertThat(row.getReferenceId()).isEqualTo(due.getId());
		assertThat(row.getRecipientUser().getId()).isEqualTo(customer.getId());
		assertThat(row.getOrganization().getId()).isEqualTo(org.getId());
		// No internal ids leak into customer copy.
		assertThat(row.getMessage()).doesNotContain(due.getId().toString());
		assertThat(row.getMessage()).doesNotContain(customer.getId().toString());
		// The badge counts it: the center exposes reminders with no extra work.
		assertThat(notifications.countByRecipientUserIdAndReadAtIsNull(customer.getId()))
				.isEqualTo(1);
	}

	@Test
	void appointmentExactlyAtLeadTimeBoundaryIsDue() {
		appointment(NOW.plus(policy.getBefore()));

		AppointmentReminderService.SweepSummary summary = reminderService.sweepOnce();

		assertThat(summary.created()).isEqualTo(1);
		assertThat(notificationsFor(customer)).hasSize(1);
	}

	@Test
	void notYetDueAppointmentIsSkipped() {
		appointment(NOW.plus(policy.getBefore()).plusSeconds(1));

		AppointmentReminderService.SweepSummary summary = reminderService.sweepOnce();

		assertThat(summary.considered()).isEqualTo(0);
		assertThat(summary.created()).isEqualTo(0);
		assertThat(reminders.count()).isEqualTo(0);
		assertThat(notificationsFor(customer)).isEmpty();
	}

	@Test
	void alreadyStartedAppointmentIsNeverReminded() {
		appointment(NOW.minus(Duration.ofHours(1)));

		AppointmentReminderService.SweepSummary summary = reminderService.sweepOnce();

		assertThat(summary.considered()).isEqualTo(0);
		assertThat(reminders.count()).isEqualTo(0);
		assertThat(notificationsFor(customer)).isEmpty();
	}

	@Test
	void lateSchedulerStillCatchesUpWhileAppointmentStaysUpcoming() {
		// Due 22 hours ago (scheduler was down), still starts an hour out.
		appointment(NOW.plus(Duration.ofHours(1)));

		AppointmentReminderService.SweepSummary summary = reminderService.sweepOnce();

		assertThat(summary.created()).isEqualTo(1);
		assertThat(notificationsFor(customer)).hasSize(1);
	}

	@Test
	void terminalAppointmentsAreExcluded() {
		Instant start = NOW.plus(Duration.ofHours(2));
		Appointment cancelled = appointment(start);
		cancelled.cancel();
		appointments.saveAndFlush(cancelled);
		Appointment completed = appointment(start.plus(Duration.ofHours(1)));
		completed.complete();
		appointments.saveAndFlush(completed);
		Appointment noShow = appointment(start.plus(Duration.ofHours(2)));
		noShow.markNoShow();
		appointments.saveAndFlush(noShow);

		AppointmentReminderService.SweepSummary summary = reminderService.sweepOnce();

		assertThat(summary.created()).isEqualTo(0);
		assertThat(reminders.count()).isEqualTo(0);
		assertThat(notificationsFor(customer)).isEmpty();
	}

	@Test
	void cancellationCommittedBeforeProcessingPreventsTheReminder() {
		Appointment due = appointment(NOW.plus(Duration.ofHours(2)));
		due.cancel();
		appointments.saveAndFlush(due);

		AppointmentReminderService.SweepSummary summary = reminderService.sweepOnce();

		assertThat(summary.created()).isEqualTo(0);
		assertThat(reminders.existsByAppointmentIdAndReminderType(
				due.getId(), AppointmentReminderType.UPCOMING)).isFalse();
	}

	@Test
	void secondSweepCreatesNothing() {
		appointment(NOW.plus(Duration.ofHours(2)));

		AppointmentReminderService.SweepSummary first = reminderService.sweepOnce();
		AppointmentReminderService.SweepSummary second = reminderService.sweepOnce();

		assertThat(first.created()).isEqualTo(1);
		assertThat(second.created()).isEqualTo(0);
		assertThat(second.considered()).isEqualTo(0);
		assertThat(reminders.count()).isEqualTo(1);
		assertThat(notificationsFor(customer)).hasSize(1);
	}

	@Test
	void sweepIsBoundedAndContinuesOnTheNextTick() {
		com.sourabh.smartqueue.service.Service quick = services.save(
				new com.sourabh.smartqueue.service.Service(
						branch, "Quick", Duration.ofMinutes(5)));
		int total = AppointmentReminderService.SWEEP_BATCH_SIZE + 5;
		for (int i = 0; i < total; i++) {
			Instant start = NOW.plus(Duration.ofMinutes(5L * (i + 1)));
			appointments.save(new Appointment(org, branch, quick, customer,
					start, start.plus(Duration.ofMinutes(5))));
		}
		appointments.flush();

		AppointmentReminderService.SweepSummary first = reminderService.sweepOnce();
		AppointmentReminderService.SweepSummary second = reminderService.sweepOnce();

		assertThat(first.considered()).isEqualTo(AppointmentReminderService.SWEEP_BATCH_SIZE);
		assertThat(first.created()).isEqualTo(AppointmentReminderService.SWEEP_BATCH_SIZE);
		assertThat(second.created()).isEqualTo(5);
		assertThat(reminders.count()).isEqualTo(total);
		assertThat(notificationsFor(customer)).hasSize(total);
	}

	@Test
	void crossOrganizationRecipientIsSkippedSafely() {
		// Defense-in-depth proof: the appointment constructor plus the
		// V6 composite foreign keys already make this state
		// unreachable through any persisted path, so the guard is
		// exercised with a stubbed repository rather than corrupted
		// rows (which the database would rightly reject).
		Organization orgB = new Organization("Foreign");
		User foreign = new User(orgB, "foreign@example.com", "Foreign");
		Appointment appointment = Mockito.mock(Appointment.class);
		UUID appointmentId = UUID.randomUUID();
		Mockito.when(appointment.getId()).thenReturn(appointmentId);
		Mockito.when(appointment.getOrganization()).thenReturn(org);
		Mockito.when(appointment.getCustomer()).thenReturn(foreign);
		Mockito.when(appointment.getStatus()).thenReturn(AppointmentStatus.SCHEDULED);
		Mockito.when(appointment.getStartAt()).thenReturn(NOW.plus(Duration.ofHours(2)));
		AppointmentRepository stubbed = Mockito.mock(AppointmentRepository.class);
		Mockito.when(stubbed.findReminderCandidates(
				Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
				.thenReturn(List.of(appointment));
		Mockito.when(stubbed.findById(appointmentId))
				.thenReturn(Optional.of(appointment));
		AppointmentReminderService guarded = new AppointmentReminderService(policy,
				stubbed, reminders, notifications, sender, clock, transactionManager);

		AppointmentReminderService.SweepSummary summary = guarded.sweepOnce();

		assertThat(summary.created()).isEqualTo(0);
		assertThat(summary.skipped()).isEqualTo(1);
		assertThat(reminders.count()).isEqualTo(0);
		assertThat(notifications.count()).isEqualTo(0);
	}

	@Test
	void realtimeSenderFailureKeepsThePersistedReminder() {
		appointment(NOW.plus(Duration.ofHours(2)));
		NotificationSender failing = notification -> {
			throw new RuntimeException("broker down");
		};
		AppointmentReminderService fragile = new AppointmentReminderService(policy,
				appointments, reminders, notifications, failing, clock, transactionManager);

		AppointmentReminderService.SweepSummary summary = fragile.sweepOnce();

		// Same rule as NotificationService: the committed rows are the
		// notification; the echo is best-effort and never fails the run.
		assertThat(summary.created()).isEqualTo(1);
		assertThat(summary.failures()).isEqualTo(0);
		assertThat(reminders.count()).isEqualTo(1);
		assertThat(notificationsFor(customer)).hasSize(1);
	}

	@Test
	void repositoryFailurePropagatesFromSweepButNeverEscapesTheScheduledTick() {
		AppointmentRepository failing = Mockito.mock(AppointmentRepository.class);
		Mockito.when(failing.findReminderCandidates(
				Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
				.thenThrow(new RuntimeException("database down"));
		AppointmentReminderService fragile = new AppointmentReminderService(policy,
				failing, reminders, notifications, sender, clock, transactionManager);

		assertThatThrownBy(fragile::sweepOnce).isInstanceOf(RuntimeException.class);
		new AppointmentReminderScheduler(fragile).scheduledReminders();
		Mockito.verify(failing, Mockito.atLeastOnce()).findReminderCandidates(
				Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
	}

	@Test
	void messageRendersBranchLocalTimeWithoutInternalIds() {
		// 2026-10-07T23:00Z is 04:30 IST on 8 Oct in Asia/Kolkata.
		Appointment due = appointment(Instant.parse("2026-10-07T23:00:00Z"));

		String message = AppointmentReminderService.messageFor(due);

		assertThat(message).contains(consultation.getName());
		assertThat(message).contains(branch.getName());
		assertThat(message).contains("4:30 AM");
		assertThat(message).contains("(IST)");
		assertThat(message).doesNotContain(due.getId().toString());
	}

	@Test
	void sameInstantRendersDifferentlyPerBranchTimezone() {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		Branch eastern = branches.save(
				new Branch(org, "East " + tag, "New York", "USA", NEW_YORK));
		com.sourabh.smartqueue.service.Service easternService = services.save(
				new com.sourabh.smartqueue.service.Service(
						eastern, "Consultation " + tag, Duration.ofMinutes(30)));
		// A second customer: the no_customer_double_booking exclusion
		// forbids one customer holding two overlapping appointments at
		// the same instant (double-booking prevention), so the same
		// instant at two branches needs two customers. The services
		// already differ, so only the customer leg would collide.
		User american = users.save(new User(org,
				"rem-east-" + tag + "@example.com", "Rem East " + tag));
		american.grantRole(RoleType.CUSTOMER);
		american = users.save(american);
		Instant start = NOW.plus(Duration.ofHours(2));
		Appointment indian = appointment(start);
		Appointment easternAppointment = appointments.saveAndFlush(new Appointment(
				org, eastern, easternService, american, start, start.plus(Duration.ofMinutes(30))));

		AppointmentReminderService.SweepSummary summary = reminderService.sweepOnce();

		assertThat(summary.created()).isEqualTo(2);
		List<Notification> indianRows = notificationsFor(customer);
		List<Notification> easternRows = notificationsFor(american);
		assertThat(indianRows).hasSize(1);
		assertThat(easternRows).hasSize(1);
		assertThat(indianRows.get(0).getMessage()).contains("(IST)");
		assertThat(easternRows.get(0).getMessage()).contains("(EDT)");
		assertThat(AppointmentReminderService.messageFor(indian))
				.isNotEqualTo(AppointmentReminderService.messageFor(easternAppointment));
	}

	@Test
	void springForwardTransitionRendersCorrectLocalTime() {
		// DST starts in America/New_York on 2026-03-08 (02:00 EST -> 03:00 EDT).
		Appointment afterTransition = transientAppointment(NEW_YORK,
				Instant.parse("2026-03-09T13:00:00Z"));

		String message = AppointmentReminderService.messageFor(afterTransition);

		assertThat(message).contains("9:00 AM");
		assertThat(message).contains("(EDT)");
	}

	@Test
	void dueInstantCrossingSpringForwardUsesInstantArithmetic() {
		// 10:00 AM EDT on transition day; the due instant (24h earlier)
		// falls on EST. Instant arithmetic lands on 09:00 AM EST —
		// naive wall-clock subtraction would claim 10:00 AM instead.
		Instant start = Instant.parse("2026-03-08T14:00:00Z");
		Instant due = start.minus(Duration.ofHours(24));

		assertThat(start.atZone(NEW_YORK).getHour()).isEqualTo(10);
		assertThat(start.atZone(NEW_YORK).getZone().getId()).isEqualTo("America/New_York");
		assertThat(due.atZone(NEW_YORK).getHour()).isEqualTo(9);

		String message = AppointmentReminderService.messageFor(
				transientAppointment(NEW_YORK, start));
		assertThat(message).contains("10:00 AM");
		assertThat(message).contains("(EDT)");
	}

	@Test
	void fallBackTransitionRendersStandardTime() {
		// DST ends 2026-11-01; Nov 2 is EST (UTC-5).
		Appointment afterFallback = transientAppointment(NEW_YORK,
				Instant.parse("2026-11-02T14:00:00Z"));

		String message = AppointmentReminderService.messageFor(afterFallback);

		assertThat(message).contains("9:00 AM");
		assertThat(message).contains("(EST)");
	}

	@Test
	void utcBranchRendersWithoutDstShift() {
		Appointment utc = transientAppointment(ZoneId.of("UTC"),
				Instant.parse("2026-10-07T10:00:00Z"));

		String message = AppointmentReminderService.messageFor(utc);

		assertThat(message).contains("10:00 AM");
		assertThat(message).contains("(UTC)");
	}

	/**
	 * In-memory appointment for pure rendering tests: no database row,
	 * no Clock involvement — only the stored instant plus the
	 * branch ZoneId feed the formatter.
	 */
	private Appointment transientAppointment(ZoneId zone, Instant start) {
		Organization transientOrg = new Organization("Transient");
		Branch transientBranch = new Branch(transientOrg, "Transient Branch",
				"City", "Country", zone);
		com.sourabh.smartqueue.service.Service transientService =
				new com.sourabh.smartqueue.service.Service(
						transientBranch, "Transient Service", Duration.ofMinutes(30));
		User transientCustomer = new User(transientOrg, "transient@example.com", "Transient");
		return new Appointment(transientOrg, transientBranch, transientService,
				transientCustomer, start, start.plus(Duration.ofMinutes(30)));
	}
}
