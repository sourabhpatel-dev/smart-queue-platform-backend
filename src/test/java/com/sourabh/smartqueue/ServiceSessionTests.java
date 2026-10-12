package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.counter.CounterCapability;
import com.sourabh.smartqueue.counter.CounterCapabilityRepository;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.CounterCannotServeQueueException;
import com.sourabh.smartqueue.queue.InvalidQueueRequestException;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueCallService;
import com.sourabh.smartqueue.queue.QueueEntry;
import com.sourabh.smartqueue.queue.QueueEntryLifecycleService;
import com.sourabh.smartqueue.queue.QueueEntryNotFoundException;
import com.sourabh.smartqueue.queue.QueueEntryRepository;
import com.sourabh.smartqueue.queue.QueueEntryStateException;
import com.sourabh.smartqueue.queue.QueueEntryStatus;
import com.sourabh.smartqueue.queue.QueueJoinService;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.ServiceSession;
import com.sourabh.smartqueue.queue.ServiceSessionRepository;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Phase 2G session tests: ServiceSession persistence, lifecycle integration
 * and ownership against real PostgreSQL (see ADR-013). Each test rolls back.
 * Own container per test class.
 */
@SpringBootTest
@Testcontainers
@Transactional
class ServiceSessionTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	static final Instant T0 = Instant.parse("2026-10-06T04:30:00Z");

	/** Manually advanced clock so session timestamps are exact, never wall-clock. */
	static class ManualClock extends Clock {
		private final AtomicReference<Instant> now = new AtomicReference<>(T0);

		void advance(Duration duration) {
			now.updateAndGet(current -> current.plus(duration));
		}

		void reset() {
			now.set(T0);
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
	QueueEntryRepository entries;

	@Autowired
	ServiceSessionRepository sessions;

	@Autowired
	UserRepository users;

	@Autowired
	QueueJoinService joinService;

	@Autowired
	QueueCallService callService;

	@Autowired
	QueueEntryLifecycleService lifecycle;

	@Autowired
	ManualClock clock;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Flyway flyway;

	Organization org;
	Branch branch;
	Service consultation;
	ServiceCounter counter1;
	Queue queue;

	@BeforeEach
	void setUp() {
		// The clock bean is shared across tests in this class: reset it so
		// every test starts from T0 regardless of execution order.
		clock.reset();
		org = organizations.save(new Organization("City Clinic"));
		branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		consultation = services.save(new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		counter1 = counters.save(new ServiceCounter(branch, "Counter 1"));
		capabilities.save(new CounterCapability(branch, consultation, counter1));
		queue = queues.save(new Queue(org, branch, consultation));
		TenantContextHolder.set(new TenantContext(org.getId(), branch.getId()));
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private User newCustomer(String email) {
		User customer = users.save(new User(org, email, "Customer"));
		customer.grantRole(RoleType.CUSTOMER);
		return customer;
	}

	private QueueEntry calledEntry() {
		QueueEntry entry = joinService.join(queue.getId(),
				newCustomer("c" + UUID.randomUUID() + "@example.com").getId(),
				QueuePriority.NORMAL);
		return callService.callNext(counter1.getId());
	}

	@Test
	void startCreatesSessionWithExactTimestamps() {
		QueueEntry called = calledEntry();

		QueueEntry serving = lifecycle.startServing(called.getId(), counter1.getId());

		assertThat(serving.getStatus()).isEqualTo(QueueEntryStatus.SERVING);
		ServiceSession session = sessions.findByQueueEntryId(called.getId()).orElseThrow();
		assertThat(session.getStartedAt()).isEqualTo(T0);
		assertThat(session.getEndedAt()).isNull();
		assertThat(session.isActive()).isTrue();
		assertThat(session.getCounter().getId()).isEqualTo(counter1.getId());
		assertThat(session.getService().getId()).isEqualTo(consultation.getId());
		assertThat(session.getBranch().getId()).isEqualTo(branch.getId());
		assertThat(session.getQueueEntry().getId()).isEqualTo(called.getId());
	}

	@Test
	void completeEndsSessionWithExactTimestamps() {
		QueueEntry called = calledEntry();
		lifecycle.startServing(called.getId(), counter1.getId());
		clock.advance(Duration.ofMinutes(12).plusSeconds(30));

		QueueEntry completed = lifecycle.complete(called.getId());

		assertThat(completed.getStatus()).isEqualTo(QueueEntryStatus.COMPLETED);
		assertThat(completed.getCompletedAt()).isNotNull();
		ServiceSession session = sessions.findByQueueEntryId(called.getId()).orElseThrow();
		assertThat(session.getEndedAt()).isEqualTo(T0.plus(Duration.ofMinutes(12).plusSeconds(30)));
		assertThat(session.isActive()).isFalse();
		assertThat(session.getEndedAt()).isAfter(session.getStartedAt());
	}

	@Test
	void sessionTimestampsUseTimestamptz() {
		QueueEntry called = calledEntry();
		lifecycle.startServing(called.getId(), counter1.getId());
		ServiceSession session = sessions.findByQueueEntryId(called.getId()).orElseThrow();

		String startedType = jdbcTemplate.queryForObject(
				"SELECT data_type FROM information_schema.columns"
						+ " WHERE table_name = 'service_session' AND column_name = 'started_at'",
				String.class);
		String endedType = jdbcTemplate.queryForObject(
				"SELECT data_type FROM information_schema.columns"
						+ " WHERE table_name = 'service_session' AND column_name = 'ended_at'",
				String.class);
		assertThat(startedType).isEqualTo("timestamp with time zone");
		assertThat(endedType).isEqualTo("timestamp with time zone");
		assertThat(session.getStartedAt()).isEqualTo(T0);
	}

	@Test
	void duplicateSessionForEntryIsRejectedByDatabase() {
		QueueEntry called = calledEntry();
		lifecycle.startServing(called.getId(), counter1.getId());
		ServiceSession session = sessions.findByQueueEntryId(called.getId()).orElseThrow();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO service_session (id, branch_id, queue_entry_id,"
						+ " service_counter_id, service_id, started_at,"
						+ " created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
				UUID.randomUUID(), branch.getId(), session.getQueueEntry().getId(),
				counter1.getId(), consultation.getId(), java.sql.Timestamp.from(T0)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void sessionRequiresExistingEntry() {
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO service_session (id, branch_id, queue_entry_id,"
						+ " service_counter_id, service_id, started_at,"
						+ " created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
				UUID.randomUUID(), branch.getId(), UUID.randomUUID(),
				counter1.getId(), consultation.getId(), java.sql.Timestamp.from(T0)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void crossBranchCounterIsRejected() {
		Branch uptown = branches.save(new Branch(org, "Uptown", "Bhopal", "India", KOLKATA));
		ServiceCounter uptownCounter = counters.save(new ServiceCounter(uptown, "Counter 1"));
		QueueEntry called = calledEntry();

		assertThatThrownBy(() -> lifecycle.startServing(called.getId(), uptownCounter.getId()))
				.isInstanceOf(CounterCannotServeQueueException.class);

		assertThat(entries.findById(called.getId()).orElseThrow().getStatus())
				.isEqualTo(QueueEntryStatus.CALLED);
		assertThat(sessions.findByQueueEntryId(called.getId())).isEmpty();
	}

	@Test
	void unsupportedCounterIsRejected() {
		Service pharmacy = services.save(new Service(branch, "Pharmacy", Duration.ofMinutes(10)));
		ServiceCounter pharmacyCounter = counters.save(new ServiceCounter(branch, "Counter 9"));
		capabilities.save(new CounterCapability(branch, pharmacy, pharmacyCounter));
		QueueEntry called = calledEntry();

		assertThatThrownBy(() -> lifecycle.startServing(called.getId(), pharmacyCounter.getId()))
				.isInstanceOf(CounterCannotServeQueueException.class);

		assertThat(entries.findById(called.getId()).orElseThrow().getStatus())
				.isEqualTo(QueueEntryStatus.CALLED);
		assertThat(sessions.findByQueueEntryId(called.getId())).isEmpty();
	}

	@Test
	void mismatchedServiceIsRejectedByDomainGuard() {
		Service other = services.save(new Service(branch, "Blood Test", Duration.ofMinutes(15)));
		QueueEntry called = calledEntry();

		assertThatThrownBy(() -> new ServiceSession(branch, called, counter1, other, T0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void sameBranchDifferentServicePassesDatabaseButIsForbiddenByDomain() {
		// Documents the deliberate boundary: composites pin branch (not shown
		// failing here), while service equality is application-enforced.
		Service other = services.save(new Service(branch, "Blood Test", Duration.ofMinutes(15)));
		QueueEntry called = calledEntry();

		jdbcTemplate.update(
				"INSERT INTO service_session (id, branch_id, queue_entry_id,"
						+ " service_counter_id, service_id, started_at,"
						+ " created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
				UUID.randomUUID(), branch.getId(), called.getId(),
				counter1.getId(), other.getId(), java.sql.Timestamp.from(T0));

		Integer rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM service_session WHERE service_id = ?",
				Integer.class, other.getId());
		assertThat(rows).isEqualTo(1);
	}

	@Test
	void unknownCounterIsRejected() {
		QueueEntry called = calledEntry();

		assertThatThrownBy(() -> lifecycle.startServing(called.getId(), UUID.randomUUID()))
				.isInstanceOf(InvalidQueueRequestException.class);
	}

	@Test
	void startServingRequiresCalledEntry() {
		QueueEntry waiting = joinService.join(queue.getId(),
				newCustomer("w@example.com").getId(), QueuePriority.NORMAL);

		assertThatThrownBy(() -> lifecycle.startServing(waiting.getId(), counter1.getId()))
				.isInstanceOf(QueueEntryStateException.class);
		assertThat(sessions.findByQueueEntryId(waiting.getId())).isEmpty();
	}

	@Test
	void completeRequiresServingEntry() {
		QueueEntry called = calledEntry();

		assertThatThrownBy(() -> lifecycle.complete(called.getId()))
				.isInstanceOf(QueueEntryStateException.class);
	}

	@Test
	void servingEntryCannotStartSecondSession() {
		QueueEntry called = calledEntry();
		lifecycle.startServing(called.getId(), counter1.getId());

		assertThatThrownBy(() -> lifecycle.startServing(called.getId(), counter1.getId()))
				.isInstanceOf(QueueEntryStateException.class);

		Integer sessions = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM service_session WHERE queue_entry_id = ?",
				Integer.class, called.getId());
		assertThat(sessions).isEqualTo(1);
	}

	@Test
	void doubleCompletionIsRejected() {
		QueueEntry called = calledEntry();
		lifecycle.startServing(called.getId(), counter1.getId());
		// End must strictly follow start, so move the clock before completing.
		clock.advance(Duration.ofMinutes(5));
		lifecycle.complete(called.getId());

		assertThatThrownBy(() -> lifecycle.complete(called.getId()))
				.isInstanceOf(QueueEntryStateException.class);
	}

	@Test
	void sessionEndGuards() {
		QueueEntry called = calledEntry();
		lifecycle.startServing(called.getId(), counter1.getId());
		ServiceSession session = sessions.findByQueueEntryId(called.getId()).orElseThrow();

		assertThatThrownBy(() -> session.end(T0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> session.end(T0.minusSeconds(1)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void unknownSessionEntryIsRejected() {
		UUID ghost = UUID.randomUUID();

		assertThatThrownBy(() -> lifecycle.complete(ghost))
				.isInstanceOf(QueueEntryNotFoundException.class);
	}

	@Test
	void flywayAppliedV8DomainMigration() {
		assertThat(flyway.info().applied())
				.anySatisfy(applied -> assertThat(applied.getVersion().getVersion()).isEqualTo("8"));

		Integer tables = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables"
						+ " WHERE table_schema = 'public' AND table_name = 'service_session'",
				Integer.class);
		assertThat(tables).isEqualTo(1);
	}
}
