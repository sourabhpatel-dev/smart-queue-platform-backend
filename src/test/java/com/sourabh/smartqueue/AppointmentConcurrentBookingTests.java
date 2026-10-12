package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.appointment.Appointment;
import com.sourabh.smartqueue.appointment.AppointmentBookingService;
import com.sourabh.smartqueue.appointment.AppointmentConflictException;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.workinghour.WorkingHour;
import com.sourabh.smartqueue.workinghour.WorkingHourRepository;

/**
 * Genuine concurrency proof for appointment booking (see ADR-011): two
 * threads race to book the identical customer/service/slot.
 *
 * <p>Exactly one must succeed; the other must see
 * {@link AppointmentConflictException}; the database must end up with
 * exactly one row. Each racing call runs in its own transaction (the
 * booking service is {@code @Transactional}); this class is deliberately
 * NOT {@code @Transactional} so nothing is shared or rolled back across
 * threads. Own container per test class.
 */
@SpringBootTest
@Testcontainers
class AppointmentConcurrentBookingTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	OrganizationRepository organizations;

	@Autowired
	BranchRepository branches;

	@Autowired
	ServiceRepository services;

	@Autowired
	UserRepository users;

	@Autowired
	WorkingHourRepository workingHours;

	@Autowired
	AppointmentBookingService booking;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void concurrentConflictingBookingsYieldExactlyOneAppointment() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		Organization org = organizations.save(new Organization("Clinic " + tag));
		Branch branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		Service consultation = services.save(
				new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		User customer = users.save(new User(org, "race-" + tag + "@example.com", "Racer"));
		customer.grantRole(RoleType.CUSTOMER);
		workingHours.save(new WorkingHour(branch, DayOfWeek.MONDAY,
				LocalTime.of(9, 0), LocalTime.of(13, 0)));
		Instant slot = LocalDate.of(2026, 10, 5).atTime(10, 0).atZone(KOLKATA).toInstant();

		UUID customerId = customer.getId();
		UUID serviceId = consultation.getId();
		CountDownLatch startGate = new CountDownLatch(1);
		AtomicInteger successes = new AtomicInteger();
		AtomicInteger conflicts = new AtomicInteger();
		Callable<Void> attempt = () -> {
			startGate.await(10, TimeUnit.SECONDS);
			try {
				Appointment booked = booking.book(customerId, serviceId, slot);
				assertThat(booked.getId()).isNotNull();
				successes.incrementAndGet();
			} catch (AppointmentConflictException e) {
				conflicts.incrementAndGet();
			}
			return null;
		};

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<Void>> futures = new ArrayList<>();
			futures.add(pool.submit(attempt));
			futures.add(pool.submit(attempt));
			startGate.countDown();
			for (Future<Void> future : futures) {
				future.get(60, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(successes.get()).isEqualTo(1);
		assertThat(conflicts.get()).isEqualTo(1);

		Integer rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM appointment WHERE service_id = ? AND status = 'SCHEDULED'",
				Integer.class, serviceId);
		assertThat(rows).isEqualTo(1);
	}
}
