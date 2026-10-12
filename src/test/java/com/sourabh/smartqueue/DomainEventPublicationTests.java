package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.appointment.AppointmentBookingService;
import com.sourabh.smartqueue.appointment.AppointmentStatus;
import com.sourabh.smartqueue.appointment.event.AppointmentBooked;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.counter.CounterCapability;
import com.sourabh.smartqueue.counter.CounterCapabilityRepository;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueCallService;
import com.sourabh.smartqueue.queue.QueueEntry;
import com.sourabh.smartqueue.queue.QueueEntryLifecycleService;
import com.sourabh.smartqueue.queue.QueueEntryStatus;
import com.sourabh.smartqueue.queue.QueueJoinService;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.event.QueueEntryCalled;
import com.sourabh.smartqueue.queue.event.QueueEntryCancelled;
import com.sourabh.smartqueue.queue.event.QueueEntryCompleted;
import com.sourabh.smartqueue.queue.event.QueueEntryJoined;
import com.sourabh.smartqueue.queue.event.QueueEntryNoShow;
import com.sourabh.smartqueue.queue.event.QueueEntryServingStarted;
import com.sourabh.smartqueue.queue.event.ServiceSessionCompleted;
import com.sourabh.smartqueue.queue.event.ServiceSessionStarted;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.workinghour.WorkingHour;
import com.sourabh.smartqueue.workinghour.WorkingHourRepository;

/**
 * Phase 3A event tests: business operations publish facts, and AFTER_COMMIT
 * consumers see them only for committed transactions (see ADR-016).
 *
 * <p>Deliberately NOT {@code @Transactional}: assertions need real commits
 * (AFTER_COMMIT fires) and real rollbacks (it must not fire). Unique data
 * per test inside this class's own container, so no cleanup is needed.
 * Fixed project Clock: event timestamps are exact, never wall-clock.
 */
@SpringBootTest
@Testcontainers
class DomainEventPublicationTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	static final Instant NOW = Instant.now();
	static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);

	static class ManualClock extends Clock {
		private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

		void advance(java.time.Duration duration) {
			now.updateAndGet(current -> current.plus(duration));
		}

		void reset() {
			now.set(NOW);
		}

		@Override
		public Instant instant() {
			return now.get();
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException("fixed test clock");
		}
	}

	@TestConfiguration
	static class FixedClockConfiguration {
		@Bean
		@Primary
		ManualClock manualClock() {
			return new ManualClock();
		}
	}

	/**
	 * Test-only stand-in for future infrastructure (Redis bridge, WebSocket
	 * broadcaster, notifier): records every event delivered AFTER_COMMIT.
	 */
	@TestConfiguration
	static class EventCollector {
		private final List<Object> received =
				Collections.synchronizedList(new ArrayList<>());

		@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
		public void onEvent(Object event) {
			received.add(event);
		}

		void clear() {
			received.clear();
		}

		<T> List<T> ofType(Class<T> type) {
			return received.stream()
					.filter(type::isInstance)
					.map(type::cast)
					.toList();
		}
	}

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
	ServiceCounterRepository counters;

	@Autowired
	CounterCapabilityRepository capabilities;

	@Autowired
	QueueRepository queues;

	@Autowired
	UserRepository users;

	@Autowired
	WorkingHourRepository workingHours;

	@Autowired
	QueueJoinService joinService;

	@Autowired
	QueueCallService callService;

	@Autowired
	QueueEntryLifecycleService lifecycle;

	@Autowired
	AppointmentBookingService booking;

	@Autowired
	EventCollector collector;

	@Autowired
	ManualClock clock;

	private Fixture fixture() {
		// Committed data survives across tests in this class (no rollback):
		// globally unique emails keep the uq_user_email constraint happy.
		String tag = "ev-" + UUID.randomUUID().toString().substring(0, 8);
		Organization org = organizations.save(new Organization("Clinic " + tag));
		Branch branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		Service consultation = services.save(
				new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		ServiceCounter counter = counters.save(new ServiceCounter(branch, "Counter 1"));
		capabilities.save(new CounterCapability(branch, consultation, counter));
		Queue queue = queues.save(new Queue(org, branch, consultation));
		User customer = users.save(new User(org, tag + "@example.com", "Customer"));
		customer.grantRole(RoleType.CUSTOMER);
		workingHours.save(new WorkingHour(branch, DayOfWeek.MONDAY,
				LocalTime.of(9, 0), LocalTime.of(18, 0)));
		TenantContextHolder.set(new TenantContext(org.getId(), branch.getId()));
		return new Fixture(org, branch, consultation, counter, queue, customer);
	}

	private record Fixture(Organization org, Branch branch, Service service,
			ServiceCounter counter, Queue queue, User customer) {
	}

	private Instant mondayAt(int hour, int minute) {
		return MONDAY.atTime(hour, minute).atZone(KOLKATA).toInstant();
	}

	@BeforeEach
	void setUp() {
		// Shared clock bean: reset so every test observes NOW regardless of
		// execution order (another test may advance it).
		clock.reset();
		collector.clear();
		TenantContextHolder.clear();
	}

	@Test
	void joinPublishesJoinedWithFullPayload() {
		Fixture f = fixture();

		QueueEntry entry = joinService.join(f.queue().getId(), f.customer().getId(),
				QueuePriority.PRIORITY);

		List<QueueEntryJoined> events = collector.ofType(QueueEntryJoined.class);
		assertThat(events).hasSize(1);
		QueueEntryJoined event = events.get(0);
		assertThat(event.eventId()).isNotNull().isNotEqualTo(entry.getId());
		assertThat(event.occurredAt()).isEqualTo(NOW);
		assertThat(event.organizationId()).isEqualTo(f.org().getId());
		assertThat(event.branchId()).isEqualTo(f.branch().getId());
		assertThat(event.queueId()).isEqualTo(f.queue().getId());
		assertThat(event.queueEntryId()).isEqualTo(entry.getId());
		assertThat(event.serviceId()).isEqualTo(f.service().getId());
		assertThat(event.customerId()).isEqualTo(f.customer().getId());
		assertThat(event.tokenNumber()).isEqualTo(entry.getTokenNumber());
		assertThat(event.businessDate()).isEqualTo(entry.getBusinessDate());
		assertThat(event.priority()).isEqualTo(QueuePriority.PRIORITY);
	}

	@Test
	void callPublishesCalledWithCounter() {
		Fixture f = fixture();
		QueueEntry entry = joinService.join(f.queue().getId(), f.customer().getId(),
				QueuePriority.NORMAL);

		callService.callNext(f.counter().getId());

		List<QueueEntryCalled> events = collector.ofType(QueueEntryCalled.class);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).queueEntryId()).isEqualTo(entry.getId());
		assertThat(events.get(0).counterId()).isEqualTo(f.counter().getId());
		assertThat(events.get(0).occurredAt()).isEqualTo(NOW);
	}

	@Test
	void startServingPublishesServingStartedAndSessionStarted() {
		Fixture f = fixture();
		QueueEntry entry = joinService.join(f.queue().getId(), f.customer().getId(),
				QueuePriority.NORMAL);
		callService.callNext(f.counter().getId());

		lifecycle.startServing(entry.getId(), f.counter().getId());

		List<QueueEntryServingStarted> serving = collector.ofType(QueueEntryServingStarted.class);
		List<ServiceSessionStarted> sessions = collector.ofType(ServiceSessionStarted.class);
		assertThat(serving).hasSize(1);
		assertThat(sessions).hasSize(1);
		assertThat(serving.get(0).queueEntryId()).isEqualTo(entry.getId());
		assertThat(sessions.get(0).queueEntryId()).isEqualTo(entry.getId());
		assertThat(sessions.get(0).serviceSessionId()).isNotNull();
		assertThat(sessions.get(0).serviceCounterId()).isEqualTo(f.counter().getId());
		assertThat(sessions.get(0).startedAt()).isEqualTo(NOW);
		assertThat(serving.get(0).eventId()).isNotEqualTo(sessions.get(0).eventId());
	}

	@Test
	void completePublishesCompletedAndSessionCompleted() {
		Fixture f = fixture();
		QueueEntry entry = joinService.join(f.queue().getId(), f.customer().getId(),
				QueuePriority.NORMAL);
		callService.callNext(f.counter().getId());
		lifecycle.startServing(entry.getId(), f.counter().getId());
		// Session end must strictly follow start: move the fixed clock first.
		clock.advance(java.time.Duration.ofMinutes(5));
		collector.clear();

		lifecycle.complete(entry.getId());

		List<QueueEntryCompleted> completed = collector.ofType(QueueEntryCompleted.class);
		List<ServiceSessionCompleted> sessions = collector.ofType(ServiceSessionCompleted.class);
		assertThat(completed).hasSize(1);
		assertThat(sessions).hasSize(1);
		assertThat(completed.get(0).queueEntryId()).isEqualTo(entry.getId());
		assertThat(sessions.get(0).queueEntryId()).isEqualTo(entry.getId());
		// The clock advanced 5 minutes before completion: the event records
		// the advanced instant, proving occurredAt follows the Clock.
		assertThat(sessions.get(0).endedAt()).isEqualTo(NOW.plus(java.time.Duration.ofMinutes(5)));
		assertThat(sessions.get(0).occurredAt()).isEqualTo(NOW.plus(java.time.Duration.ofMinutes(5)));
	}

	@Test
	void cancelPublishesCancelled() {
		Fixture f = fixture();
		QueueEntry entry = joinService.join(f.queue().getId(), f.customer().getId(),
				QueuePriority.NORMAL);
		collector.clear();

		lifecycle.cancel(entry.getId());

		List<QueueEntryCancelled> events = collector.ofType(QueueEntryCancelled.class);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).queueEntryId()).isEqualTo(entry.getId());
	}

	@Test
	void noShowPublishesNoShow() {
		Fixture f = fixture();
		QueueEntry entry = joinService.join(f.queue().getId(), f.customer().getId(),
				QueuePriority.NORMAL);
		collector.clear();

		lifecycle.markNoShow(entry.getId());

		List<QueueEntryNoShow> events = collector.ofType(QueueEntryNoShow.class);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).queueEntryId()).isEqualTo(entry.getId());
	}

	@Test
	void bookingPublishesBooked() {
		Fixture f = fixture();

		var appointment = booking.book(f.customer().getId(), f.service().getId(),
				mondayAt(10, 0));

		List<AppointmentBooked> events = collector.ofType(AppointmentBooked.class);
		assertThat(events).hasSize(1);
		AppointmentBooked event = events.get(0);
		assertThat(event.eventId()).isNotNull().isNotEqualTo(appointment.getId());
		assertThat(event.occurredAt()).isEqualTo(NOW);
		assertThat(event.appointmentId()).isEqualTo(appointment.getId());
		assertThat(event.organizationId()).isEqualTo(f.org().getId());
		assertThat(event.branchId()).isEqualTo(f.branch().getId());
		assertThat(event.serviceId()).isEqualTo(f.service().getId());
		assertThat(event.customerId()).isEqualTo(f.customer().getId());
		assertThat(event.startAt()).isEqualTo(mondayAt(10, 0));
		assertThat(event.endAt()).isEqualTo(mondayAt(10, 30));
		assertThat(event.status()).isEqualTo(AppointmentStatus.SCHEDULED);
	}

	@Test
	void distinctOperationsYieldDistinctEventIds() {
		Fixture f = fixture();
		joinService.join(f.queue().getId(), f.customer().getId(), QueuePriority.NORMAL);
		User other = users.save(new User(f.org(),
				"other-" + UUID.randomUUID() + "@example.com", "Other"));
		joinService.join(f.queue().getId(), other.getId(), QueuePriority.NORMAL);

		List<QueueEntryJoined> events = collector.ofType(QueueEntryJoined.class);
		assertThat(events).hasSize(2);
		assertThat(events.get(0).eventId()).isNotEqualTo(events.get(1).eventId());
	}

	@Test
	@Transactional
	void rolledBackOperationPublishesNothing() {
		Fixture f = fixture();
		collector.clear();

		// Joins inside this test transaction, which Spring rolls back after
		// the test: AFTER_COMMIT must therefore never deliver the event.
		joinService.join(f.queue().getId(), f.customer().getId(), QueuePriority.NORMAL);

		assertThat(collector.ofType(QueueEntryJoined.class)).isEmpty();
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}
}
