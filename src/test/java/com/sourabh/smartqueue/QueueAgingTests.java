package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueCallService;
import com.sourabh.smartqueue.queue.QueueEntry;
import com.sourabh.smartqueue.queue.QueueEntryRepository;
import com.sourabh.smartqueue.queue.QueueEntryStatus;
import com.sourabh.smartqueue.queue.QueueJoinService;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Phase 2H aging tests: the effective-rank policy against real PostgreSQL
 * (see ADR-014). Policy under test (defaults: 15-minute intervals, cap 3):
 * score = base weight (PRIORITY&nbsp;=&nbsp;1, else&nbsp;0) plus capped
 * aging steps; EMERGENCY always first by construction.
 *
 * <p>Time is fully deterministic: a fixed project Clock plus backdated
 * {@code joined_at} values (a real join followed by a native timestamp
 * update — no sleeps). Each test rolls back. Own container per test class.
 */
@SpringBootTest
@Testcontainers
@Transactional
class QueueAgingTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	static final Instant NOW = Instant.parse("2026-10-06T04:30:00Z");

	static class ManualClock extends Clock {
		private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

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
	UserRepository users;

	@Autowired
	QueueJoinService joinService;

	@Autowired
	QueueCallService callService;

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

	private User newCustomer() {
		customerSequence++;
		User customer = users.save(new User(org,
				"aged" + customerSequence + "@example.com", "Aged " + customerSequence));
		customer.grantRole(RoleType.CUSTOMER);
		return customer;
	}

	/**
	 * Joins now, then rewrites {@code joined_at} to a fixed past instant —
	 * a replayed earlier join with exact, deterministic waiting time.
	 */
	private QueueEntry joinMinutesAgo(QueuePriority priority, long minutesAgo) {
		QueueEntry entry = joinService.join(queue.getId(), newCustomer().getId(), priority);
		jdbcTemplate.update("UPDATE queue_entry SET joined_at = ? WHERE id = ?",
				Timestamp.from(NOW.minus(Duration.ofMinutes(minutesAgo))), entry.getId());
		return entry;
	}

	private QueueEntry callNext() {
		return callService.callNext(counter1.getId());
	}

	@Test
	void belowThresholdNoPromotion() {
		// NORMAL waited 14 of 15 minutes (score 0) vs fresh PRIORITY (score 1).
		joinMinutesAgo(QueuePriority.NORMAL, 14);
		QueueEntry freshPriority = joinMinutesAgo(QueuePriority.PRIORITY, 0);
		entries.flush();

		assertThat(callNext().getId()).isEqualTo(freshPriority.getId());
	}

	@Test
	void atThresholdFirstPromotion() {
		// NORMAL at exactly one interval (score 1) ties fresh PRIORITY (1);
		// FIFO seniority decides — the aged entry joined first.
		QueueEntry agedNormal = joinMinutesAgo(QueuePriority.NORMAL, 15);
		joinMinutesAgo(QueuePriority.PRIORITY, 0);
		entries.flush();

		assertThat(callNext().getId()).isEqualTo(agedNormal.getId());
	}

	@Test
	void multipleThresholdsAccumulate() {
		// NORMAL at 45 minutes (score 3) outranks fresh PRIORITY (score 1).
		QueueEntry agedNormal = joinMinutesAgo(QueuePriority.NORMAL, 45);
		joinMinutesAgo(QueuePriority.PRIORITY, 0);
		entries.flush();

		assertThat(callNext().getId()).isEqualTo(agedNormal.getId());
	}

	@Test
	void capHoldsAgainstLongWaitedPriority() {
		// NORMAL at 10 hours is capped at score 3; PRIORITY at 1 hour scores
		// 1 + 3 = 4 and still wins. Without the cap the NORMAL (score 40)
		// would wrongly outrank it — this test proves the cap exists.
		joinMinutesAgo(QueuePriority.NORMAL, 600);
		QueueEntry waitedPriority = joinMinutesAgo(QueuePriority.PRIORITY, 60);
		entries.flush();

		assertThat(callNext().getId()).isEqualTo(waitedPriority.getId());
	}

	@Test
	void starvationScenarioAgedNormalBeatsFreshPriorityStream() {
		// The documented guarantee: a NORMAL that aged while PRIORITY
		// customers kept arriving is selected next — waiting is bounded
		// unless EMERGENCY arrivals are continuous.
		QueueEntry agedNormal = joinMinutesAgo(QueuePriority.NORMAL, 60);
		joinMinutesAgo(QueuePriority.PRIORITY, 5);
		joinMinutesAgo(QueuePriority.PRIORITY, 1);
		entries.flush();

		QueueEntry called = callNext();

		assertThat(called.getId()).isEqualTo(agedNormal.getId());
	}

	@Test
	void emergencyCeilingHoldsAgainstVeryOldNormal() {
		QueueEntry ancientNormal = joinMinutesAgo(QueuePriority.NORMAL, 600);
		QueueEntry freshEmergency = joinMinutesAgo(QueuePriority.EMERGENCY, 0);
		entries.flush();

		assertThat(callNext().getId()).isEqualTo(freshEmergency.getId());
		assertThat(entries.findById(ancientNormal.getId()).orElseThrow().getStatus())
				.isEqualTo(QueueEntryStatus.WAITING);
	}

	@Test
	void basePriorityNeverMutates() {
		joinMinutesAgo(QueuePriority.NORMAL, 600);
		joinMinutesAgo(QueuePriority.PRIORITY, 0);
		entries.flush();

		QueueEntry called = callNext();

		assertThat(entries.findById(called.getId()).orElseThrow().getPriority())
				.isEqualTo(QueuePriority.NORMAL);
		String stored = jdbcTemplate.queryForObject(
				"SELECT priority FROM queue_entry WHERE id = ?", String.class, called.getId());
		assertThat(stored).isEqualTo("NORMAL");
	}

	@Test
	void identicalRankFallsBackToJoinOrderThenUuid() {
		QueueEntry first = joinMinutesAgo(QueuePriority.NORMAL, 45);
		QueueEntry second = joinMinutesAgo(QueuePriority.NORMAL, 45);
		entries.flush();
		// Same score, same joined_at: PostgreSQL uuid order (unsigned byte
		// order, unlike Java's signed UUID.compareTo) decides — replicate it.
		UUID expected = compareUnsigned(first.getId(), second.getId()) < 0
				? first.getId()
				: second.getId();

		assertThat(callNext().getId()).isEqualTo(expected);
	}

	private static int compareUnsigned(UUID left, UUID right) {
		int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
		return high != 0 ? high
				: Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
	}

	@Test
	void freshQueueKeepsLegacyOrder() {
		// Zero waiting time everywhere: EMERGENCY > PRIORITY > NORMAL, FIFO —
		// the Phase 2F order, unchanged when aging has nothing to add.
		join(queue, QueuePriority.NORMAL);
		join(queue, QueuePriority.EMERGENCY);
		join(queue, QueuePriority.PRIORITY);

		assertThat(callNext().getPriority()).isEqualTo(QueuePriority.EMERGENCY);
		assertThat(callNext().getPriority()).isEqualTo(QueuePriority.PRIORITY);
		assertThat(callNext().getPriority()).isEqualTo(QueuePriority.NORMAL);
	}

	private QueueEntry join(Queue queue, QueuePriority priority) {
		return joinService.join(queue.getId(), newCustomer().getId(), priority);
	}
}
