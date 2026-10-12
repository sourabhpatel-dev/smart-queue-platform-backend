package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
import com.sourabh.smartqueue.queue.QueueJoinService;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.QueueWaitTimeEstimator;
import com.sourabh.smartqueue.queue.WaitTimeEstimate;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Phase 2I estimator tests: the position model against real PostgreSQL
 * (see ADR-015). Time is fully deterministic — a fixed project Clock plus
 * backdated {@code joined_at} values (a real join followed by a native
 * timestamp update, no sleeps). Each test rolls back. Own container.
 */
@SpringBootTest
@Testcontainers
@Transactional
class QueueWaitTimeEstimatorTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	static final Instant NOW = Instant.now();

	static class ManualClock extends Clock {
		private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

		void advance(Duration duration) {
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
	ManualClock clock;

	@Autowired
	JdbcTemplate jdbcTemplate;

	Organization org;
	Branch branch;
	Service consultation;
	ServiceCounter counter1;
	Queue queue;

	private int customerSequence = 0;

	@BeforeEach
	void setUp() {
		// Shared clock bean: reset so every test starts from NOW regardless
		// of execution order (another test may advance it).
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

	private QueueEntry join(QueuePriority priority) {
		customerSequence++;
		User customer = users.save(new User(org,
				"wait" + customerSequence + "@example.com", "Wait " + customerSequence));
		customer.grantRole(RoleType.CUSTOMER);
		return joinService.join(queue.getId(), customer.getId(), priority);
	}

	private QueueEntry joinMinutesAgo(QueuePriority priority, long minutesAgo) {
		QueueEntry entry = join(priority);
		jdbcTemplate.update("UPDATE queue_entry SET joined_at = ? WHERE id = ?",
				Timestamp.from(NOW.minus(Duration.ofMinutes(minutesAgo))), entry.getId());
		return entry;
	}

	private WaitTimeEstimate estimate(UUID entryId) {
		return estimator.estimate(entryId).orElseThrow();
	}

	@Test
	void emptyQueueMeansImmediateOpportunity() {
		QueueEntry entry = join(QueuePriority.NORMAL);

		WaitTimeEstimate estimate = estimate(entry.getId());

		assertThat(estimate.waitMinutes()).isZero();
		assertThat(estimate.entriesAhead()).isZero();
		assertThat(estimate.activeSessions()).isZero();
		assertThat(estimate.servingCounters()).isEqualTo(1);
		assertThat(estimate.estimatedStart()).isEqualTo(estimate.calculatedAt());
		assertThat(estimate.calculatedAt()).isEqualTo(NOW);
	}

	@Test
	void oneCustomerAheadMeansOneDurationUnit() {
		join(QueuePriority.NORMAL);
		QueueEntry second = join(QueuePriority.NORMAL);

		WaitTimeEstimate estimate = estimate(second.getId());

		assertThat(estimate.entriesAhead()).isEqualTo(1);
		assertThat(estimate.waitMinutes()).isEqualTo(30);
		assertThat(estimate.estimatedStart()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
	}

	@Test
	void multipleCustomersAheadScaleLinearly() {
		join(QueuePriority.NORMAL);
		join(QueuePriority.NORMAL);
		join(QueuePriority.NORMAL);
		QueueEntry fourth = join(QueuePriority.NORMAL);

		assertThat(estimate(fourth.getId()).waitMinutes()).isEqualTo(90);
	}

	@Test
	void normalBehindPriorityWaits() {
		joinMinutesAgo(QueuePriority.NORMAL, 1);
		joinMinutesAgo(QueuePriority.PRIORITY, 0);

		// Fresh NORMAL sits behind the PRIORITY entry.
		List<WaitTimeEstimate> estimates = List.of(
				estimate(joinMinutesAgo(QueuePriority.NORMAL, 0).getId()));
		assertThat(estimates.get(0).waitMinutes()).isEqualTo(60);
	}

	@Test
	void normalBehindEmergencyWaits() {
		joinMinutesAgo(QueuePriority.NORMAL, 1);
		joinMinutesAgo(QueuePriority.EMERGENCY, 0);
		QueueEntry normal = joinMinutesAgo(QueuePriority.NORMAL, 0);

		assertThat(estimate(normal.getId()).waitMinutes()).isEqualTo(60);
	}

	@Test
	void agingNarrowsTheWait() {
		// Aged NORMAL (60 min, score 3) ties-or-beats fresh PRIORITY (1):
		// only the aged NORMAL itself is ahead of nobody here.
		QueueEntry aged = joinMinutesAgo(QueuePriority.NORMAL, 60);
		joinMinutesAgo(QueuePriority.PRIORITY, 0);

		WaitTimeEstimate agedEstimate = estimate(aged.getId());

		assertThat(agedEstimate.entriesAhead()).isZero();
		assertThat(agedEstimate.waitMinutes()).isZero();
	}

	@Test
	void twoCountersHalveTheRounds() {
		ServiceCounter counter2 = counters.save(new ServiceCounter(branch, "Counter 2"));
		capabilities.save(new CounterCapability(branch, consultation, counter2));
		join(QueuePriority.NORMAL);
		join(QueuePriority.NORMAL);
		QueueEntry third = join(QueuePriority.NORMAL);

		// Two ahead, two counters: floor(2 / 2) = 1 round = 30 minutes
		// (one counter would give 60).
		assertThat(estimate(third.getId()).waitMinutes()).isEqualTo(30);
	}

	@Test
	void activeSessionCountsAsOneUnit() {
		QueueEntry serving = join(QueuePriority.NORMAL);
		callService.callNext(counter1.getId());
		lifecycle.startServing(serving.getId(), counter1.getId());
		QueueEntry waiting = join(QueuePriority.NORMAL);

		WaitTimeEstimate estimate = estimate(waiting.getId());

		assertThat(estimate.entriesAhead()).isZero();
		assertThat(estimate.activeSessions()).isEqualTo(1);
		assertThat(estimate.waitMinutes()).isEqualTo(30);
	}

	@Test
	void multipleActiveSessionsAcrossCounters() {
		ServiceCounter counter2 = counters.save(new ServiceCounter(branch, "Counter 2"));
		capabilities.save(new CounterCapability(branch, consultation, counter2));
		QueueEntry first = join(QueuePriority.NORMAL);
		QueueEntry second = join(QueuePriority.NORMAL);
		callService.callNext(counter1.getId());
		lifecycle.startServing(first.getId(), counter1.getId());
		callService.callNext(counter2.getId());
		lifecycle.startServing(second.getId(), counter2.getId());
		QueueEntry waiting = join(QueuePriority.NORMAL);

		// Two busy counters, nobody waiting ahead: floor(2 / 2) = 1 round.
		assertThat(estimate(waiting.getId()).waitMinutes()).isEqualTo(30);
	}

	@Test
	void differentServiceDurationScalesTheWait() {
		Service bloodTest = services.save(new Service(branch, "Blood Test", Duration.ofMinutes(45)));
		Queue bloodQueue = queues.save(new Queue(org, branch, bloodTest));
		capabilities.save(new CounterCapability(branch, bloodTest, counter1));
		customerSequence++;
		User first = users.save(new User(org, "d1@example.com", "D1"));
		joinService.join(bloodQueue.getId(), first.getId(), QueuePriority.NORMAL);
		customerSequence++;
		User second = users.save(new User(org, "d2@example.com", "D2"));
		QueueEntry waiting =
				joinService.join(bloodQueue.getId(), second.getId(), QueuePriority.NORMAL);

		assertThat(estimator.estimate(waiting.getId()).orElseThrow().waitMinutes())
				.isEqualTo(45);
	}

	@Test
	void servingEntryHasZeroWait() {
		QueueEntry entry = join(QueuePriority.NORMAL);
		callService.callNext(counter1.getId());
		lifecycle.startServing(entry.getId(), counter1.getId());

		assertThat(estimate(entry.getId()).waitMinutes()).isZero();
	}

	@Test
	void calledEntryHasZeroWait() {
		QueueEntry entry = join(QueuePriority.NORMAL);
		callService.callNext(counter1.getId());

		assertThat(estimate(entry.getId()).waitMinutes()).isZero();
	}

	@Test
	void terminalEntriesHaveNoEstimate() {
		QueueEntry completed = join(QueuePriority.NORMAL);
		callService.callNext(counter1.getId());
		lifecycle.startServing(completed.getId(), counter1.getId());
		// Session end must strictly follow start: move the fixed clock first.
		clock.advance(Duration.ofMinutes(5));
		lifecycle.complete(completed.getId());

		QueueEntry cancelled = join(QueuePriority.NORMAL);
		lifecycle.cancel(cancelled.getId());

		QueueEntry noShow = join(QueuePriority.NORMAL);
		lifecycle.markNoShow(noShow.getId());

		assertThat(estimator.estimate(completed.getId())).isEmpty();
		assertThat(estimator.estimate(cancelled.getId())).isEmpty();
		assertThat(estimator.estimate(noShow.getId())).isEmpty();
	}

	@Test
	void noCapableCounterMeansNoEstimate() {
		counter1.setStatus(CounterStatus.MAINTENANCE);
		QueueEntry entry = join(QueuePriority.NORMAL);

		assertThat(estimator.estimate(entry.getId())).isEmpty();
	}

	@Test
	void unknownEntryMeansNoEstimate() {
		assertThat(estimator.estimate(UUID.randomUUID())).isEmpty();
	}

	@Test
	void largeQueueEstimate() {
		List<QueueEntry> all = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			all.add(join(QueuePriority.NORMAL));
		}

		QueueEntry last = all.get(all.size() - 1);
		assertThat(estimate(last.getId()).waitMinutes()).isEqualTo(24 * 30);
	}

	@Test
	void estimateOrderMatchesClaimOrder() {
		// The shared-semantics proof: sorting by estimated wait must equal
		// the order callNext actually serves.
		QueueEntry normal = joinMinutesAgo(QueuePriority.NORMAL, 1);
		QueueEntry priority = joinMinutesAgo(QueuePriority.PRIORITY, 0);
		QueueEntry aged = joinMinutesAgo(QueuePriority.NORMAL, 60);

		List<UUID> byEstimate = List.of(normal, priority, aged).stream()
				.sorted(Comparator.comparingInt(entry -> estimate(entry.getId()).waitMinutes()))
				.map(QueueEntry::getId)
				.toList();
		List<UUID> byClaim = List.of(
				callService.callNext(counter1.getId()).getId(),
				callService.callNext(counter1.getId()).getId(),
				callService.callNext(counter1.getId()).getId());

		assertThat(byClaim).containsExactlyElementsOf(byEstimate);
	}
}
