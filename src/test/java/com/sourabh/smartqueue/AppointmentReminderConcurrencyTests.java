package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Pageable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.appointment.Appointment;
import com.sourabh.smartqueue.appointment.AppointmentRepository;
import com.sourabh.smartqueue.appointment.reminder.AppointmentReminderRepository;
import com.sourabh.smartqueue.appointment.reminder.AppointmentReminderType;
import com.sourabh.smartqueue.appointment.reminder.application.AppointmentReminderService;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.notification.NotificationRepository;
import com.sourabh.smartqueue.notification.NotificationType;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Concurrent reminder claiming (see ADR-034): two schedulers racing
 * the same due appointment yield one durable reminder and one
 * notification — the {@code uq_appointment_reminder} constraint
 * arbitrates, not application locking.
 *
 * <p>NOT transactional by design: racing threads need real commits
 * (a rollback test cannot prove constraint arbitration). Thread
 * coordination uses a start-gate latch only — no arbitrary sleeps.
 */
@Testcontainers
@SpringBootTest
class AppointmentReminderConcurrencyTests {

	static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");

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

	Organization org;
	Branch branch;
	com.sourabh.smartqueue.service.Service consultation;
	User customer;
	Appointment due;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		org = organizations.save(new Organization("Race Org " + tag));
		branch = branches.save(new Branch(org, "Downtown " + tag, "Bhopal", "India",
				ZoneId.of("Asia/Kolkata")));
		consultation = services.save(new com.sourabh.smartqueue.service.Service(
				branch, "Consultation " + tag, Duration.ofMinutes(30)));
		customer = users.save(new User(org, "race-" + tag + "@example.com", "Race " + tag));
		customer.grantRole(RoleType.CUSTOMER);
		customer = users.save(customer);
		Instant start = NOW.plus(Duration.ofHours(2));
		due = appointments.save(new Appointment(org, branch, consultation, customer,
				start, start.plus(Duration.ofMinutes(30))));
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	@Test
	void concurrentSweepsClaimExactlyOneReminderAndOneNotification() throws Exception {
		CountDownLatch gate = new CountDownLatch(1);
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try {
			Future<AppointmentReminderService.SweepSummary> first = workers.submit(() -> {
				gate.await(10, TimeUnit.SECONDS);
				return reminderService.sweepOnce();
			});
			Future<AppointmentReminderService.SweepSummary> second = workers.submit(() -> {
				gate.await(10, TimeUnit.SECONDS);
				return reminderService.sweepOnce();
			});
			gate.countDown();
			AppointmentReminderService.SweepSummary a = first.get(60, TimeUnit.SECONDS);
			AppointmentReminderService.SweepSummary b = second.get(60, TimeUnit.SECONDS);

			assertThat(a.created() + b.created()).isEqualTo(1);
			assertThat(reminders.countByAppointmentIdAndReminderType(
					due.getId(), AppointmentReminderType.UPCOMING)).isEqualTo(1);
			long reminderNotifications = notifications
					.findByRecipientUserId(customer.getId(), Pageable.unpaged())
					.getContent().stream()
					.filter(row -> row.getType() == NotificationType.APPOINTMENT_REMINDER
							&& due.getId().equals(row.getReferenceId()))
					.count();
			assertThat(reminderNotifications).isEqualTo(1);
		} finally {
			workers.shutdownNow();
		}
	}
}
