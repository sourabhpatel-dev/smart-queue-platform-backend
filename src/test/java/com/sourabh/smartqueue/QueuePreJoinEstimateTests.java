package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.counter.CounterCapability;
import com.sourabh.smartqueue.counter.CounterCapabilityRepository;
import com.sourabh.smartqueue.counter.CounterStatus;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueCallService;
import com.sourabh.smartqueue.queue.QueueEntry;
import com.sourabh.smartqueue.queue.QueueEntryLifecycleService;
import com.sourabh.smartqueue.queue.QueueEntryStatus;
import com.sourabh.smartqueue.queue.QueueJoinEstimate;
import com.sourabh.smartqueue.queue.QueueJoinService;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.QueueWaitTimeEstimator;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

@SpringBootTest
@Testcontainers
@Transactional
class QueuePreJoinEstimateTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	static final Instant NOW = Instant.now();

	static class ManualClock extends Clock {
		private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

		void set(Instant instant) {
			now.set(instant);
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
	UserRepository users;

	@Autowired
	QueueJoinService joinService;

	@Autowired
	QueueCallService callService;

	@Autowired
	QueueEntryLifecycleService lifecycle;

	@Autowired
	QueueWaitTimeEstimator estimator;

	@Autowired
	JdbcTemplate jdbcTemplate;

	Organization org;
	Branch branch;
	Service consultation;
	ServiceCounter counter1;
	Queue queue;
	AuthenticatedPrincipal principal;

	int customerSequence = 0;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		org = organizations.save(new Organization("Estimate Org"));
		branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		consultation = services.save(new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		counter1 = counters.save(new ServiceCounter(branch, "Counter 1"));
		capabilities.save(new CounterCapability(branch, consultation, counter1));
		queue = queues.save(new Queue(org, branch, consultation));
		principal = new AuthenticatedPrincipal(UUID.randomUUID(), org.getId(),
				"test@example.com", java.util.Set.of(RoleType.CUSTOMER));
		TenantContextHolder.set(new TenantContext(org.getId(), branch.getId()));
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private QueueEntry join(QueuePriority priority) {
		customerSequence++;
		User customer = users.save(new User(org,
				"est" + customerSequence + "@example.com", "Est " + customerSequence));
		customer.grantRole(RoleType.CUSTOMER);
		return joinService.join(queue.getId(), customer.getId(), priority);
	}

	private QueueEntry joinMinutesAgo(QueuePriority priority, long minutesAgo) {
		QueueEntry entry = join(priority);
		jdbcTemplate.update("UPDATE queue_entry SET joined_at = ? WHERE id = ?",
				Timestamp.from(NOW.minus(Duration.ofMinutes(minutesAgo))), entry.getId());
		return entry;
	}

	private QueueJoinEstimate estimateJoin() {
		return estimator.estimateJoin(queue.getId());
	}

	@Test
	void emptyQueueHasZeroWaitingAndZeroEstimate() {
		QueueJoinEstimate estimate = estimateJoin();

		assertThat(estimate.queueId()).isEqualTo(queue.getId());
		assertThat(estimate.waitingCount()).isZero();
		assertThat(estimate.estimatedWaitMinutes()).isZero();
		assertThat(estimate.capableCounterCount()).isEqualTo(1);
		assertThat(estimate.activeSessionCount()).isZero();
		assertThat(estimate.calculatedAt()).isEqualTo(NOW);
	}

	@Test
	void waitingCustomersAreCounted() {
		join(QueuePriority.NORMAL);
		join(QueuePriority.NORMAL);

		QueueJoinEstimate estimate = estimateJoin();

		assertThat(estimate.waitingCount()).isEqualTo(2);
	}

	@Test
	void oneWaitingCustomerMeansOneDurationUnit() {
		join(QueuePriority.NORMAL);

		QueueJoinEstimate estimate = estimateJoin();

		assertThat(estimate.waitingCount()).isEqualTo(1);
		assertThat(estimate.estimatedWaitMinutes()).isEqualTo(30);
	}

	@Test
	void twoWaitingCustomersWithOneCounterMeansTwoDurationUnits() {
		join(QueuePriority.NORMAL);
		join(QueuePriority.NORMAL);

		QueueJoinEstimate estimate = estimateJoin();

		assertThat(estimate.waitingCount()).isEqualTo(2);
		assertThat(estimate.estimatedWaitMinutes()).isEqualTo(60);
	}

	@Test
	void priorityOrderingIsReused() {
		joinMinutesAgo(QueuePriority.NORMAL, 1);
		joinMinutesAgo(QueuePriority.PRIORITY, 0);

		QueueJoinEstimate estimate = estimateJoin();

		assertThat(estimate.waitingCount()).isEqualTo(2);
	}

	@Test
	void agingOrderingIsReused() {
		joinMinutesAgo(QueuePriority.NORMAL, 60);
		joinMinutesAgo(QueuePriority.PRIORITY, 0);

		QueueJoinEstimate estimate = estimateJoin();

		assertThat(estimate.waitingCount()).isEqualTo(2);
	}

	@Test
	void capableCounterCountReflectsActiveCounters() {
		QueueJoinEstimate estimate = estimateJoin();

		assertThat(estimate.capableCounterCount()).isEqualTo(1);
	}

	@Test
	void multipleCapableCountersReduceWait() {
		ServiceCounter counter2 = counters.save(new ServiceCounter(branch, "Counter 2"));
		capabilities.save(new CounterCapability(branch, consultation, counter2));
		join(QueuePriority.NORMAL);
		join(QueuePriority.NORMAL);

		QueueJoinEstimate estimate = estimateJoin();

		assertThat(estimate.capableCounterCount()).isEqualTo(2);
		assertThat(estimate.waitingCount()).isEqualTo(2);
		assertThat(estimate.estimatedWaitMinutes()).isEqualTo(30);
	}

	@Test
	void zeroCapableCountersReturnsNullEstimate() {
		counter1.setStatus(CounterStatus.MAINTENANCE);
		counters.save(counter1);
		join(QueuePriority.NORMAL);

		QueueJoinEstimate estimate = estimateJoin();

		assertThat(estimate.capableCounterCount()).isZero();
		assertThat(estimate.estimatedWaitMinutes()).isNull();
	}

	@Test
	void activeSessionIsReflectedInEstimate() {
		QueueEntry serving = join(QueuePriority.NORMAL);
		callService.callNext(counter1.getId());
		lifecycle.startServing(serving.getId(), counter1.getId());
		join(QueuePriority.NORMAL);

		QueueJoinEstimate estimate = estimateJoin();

		assertThat(estimate.activeSessionCount()).isEqualTo(1);
		assertThat(estimate.waitingCount()).isEqualTo(1);
	}

	@Test
	void inactiveQueueReturnsNullEstimate() {
		// Join validation and estimate availability are distinct
		// contracts: joining an inactive queue is rejected (409 —
		// proven at the API level), so the estimate fixture joins
		// while the queue is ACTIVE and deactivates it afterwards.
		// The waiting row still exists, but no wait is meaningful:
		// the contention scope (like claiming) only covers ACTIVE
		// queues, so the estimate reports null — never a stale
		// positive number.
		QueueEntry waiting = join(QueuePriority.NORMAL);
		queue.setStatus(com.sourabh.smartqueue.queue.QueueStatus.INACTIVE);
		queues.save(queue);

		QueueJoinEstimate estimate = estimateJoin();

		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM queue_entry WHERE queue_id = ? AND status = 'WAITING'",
				Integer.class, queue.getId())).isEqualTo(1);
		assertThat(waiting.getStatus())
				.isEqualTo(com.sourabh.smartqueue.queue.QueueEntryStatus.WAITING);
		assertThat(estimate.estimatedWaitMinutes()).isNull();
	}

	@Test
	void privacyExposesOnlyAggregateInformation() {
		joinMinutesAgo(QueuePriority.NORMAL, 1);
		joinMinutesAgo(QueuePriority.PRIORITY, 0);

		QueueJoinEstimate estimate = estimateJoin();

		assertThat(estimate.queueId()).isNotNull();
		assertThat(estimate.waitingCount()).isEqualTo(2);
		assertThat(estimate.estimatedWaitMinutes()).isNotNull();
		assertThat(estimate.capableCounterCount()).isEqualTo(1);
		assertThat(estimate.activeSessionCount()).isZero();
		assertThat(estimate.calculatedAt()).isNotNull();
	}

	@Test
	void noQueueEntryIsCreatedByEstimate() {
		int before = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM queue_entry WHERE queue_id = ?", Integer.class, queue.getId());

		estimateJoin();

		int after = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM queue_entry WHERE queue_id = ?", Integer.class, queue.getId());

		assertThat(after).isEqualTo(before);
	}

	@Test
	void largeQueueEstimateIsCorrect() {
		for (int i = 0; i < 25; i++) {
			join(QueuePriority.NORMAL);
		}

		QueueJoinEstimate estimate = estimateJoin();

		assertThat(estimate.waitingCount()).isEqualTo(25);
		assertThat(estimate.estimatedWaitMinutes()).isEqualTo(25 * 30);
	}

	@Test
	void estimateFormulaMatchesPerEntryEstimator() {
		// The two models answer different questions: the per-entry
		// estimate positions one ranked entry (ahead of it), while the
		// pre-join estimate positions a hypothetical newcomer behind
		// everyone waiting. With one entry waiting, the pre-join
		// estimate (one 30-minute unit) must equal the per-entry
		// estimate of the entry that then joins at the back (one
		// entry ahead of it) — and after that join the pre-join
		// estimate moves one unit further out.
		QueueEntry first = join(QueuePriority.NORMAL);
		QueueJoinEstimate beforeSecond = estimateJoin();
		assertThat(beforeSecond.waitingCount()).isEqualTo(1);
		assertThat(beforeSecond.estimatedWaitMinutes()).isEqualTo(30);

		QueueEntry second = join(QueuePriority.NORMAL);

		var perEntryEstimate = estimator.estimate(second.getId()).orElseThrow();
		assertThat(perEntryEstimate.waitMinutes())
				.isEqualTo(beforeSecond.estimatedWaitMinutes());

		QueueJoinEstimate joinEstimate = estimateJoin();
		assertThat(joinEstimate.waitingCount()).isEqualTo(2);
		assertThat(joinEstimate.estimatedWaitMinutes()).isEqualTo(60);
		assertThat(first.getId()).isNotEqualTo(second.getId());
	}

	@Test
	void calledAheadIsReflectedInEstimate() {
		QueueEntry called = join(QueuePriority.NORMAL);
		callService.callNext(counter1.getId());

		QueueJoinEstimate estimate = estimateJoin();

		assertThat(estimate.waitingCount()).isZero();
		assertThat(estimate.estimatedWaitMinutes()).isEqualTo(30);
	}
}
