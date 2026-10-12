package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.counter.CounterCapability;
import com.sourabh.smartqueue.counter.CounterCapabilityRepository;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.infrastructure.redis.RedisJson;
import com.sourabh.smartqueue.infrastructure.redis.RedisKeyConvention;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueCallService;
import com.sourabh.smartqueue.queue.QueueEntry;
import com.sourabh.smartqueue.queue.QueueEntryLifecycleService;
import com.sourabh.smartqueue.queue.QueueEntryRepository;
import com.sourabh.smartqueue.queue.QueueJoinService;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.QueueStatus;
import com.sourabh.smartqueue.queue.realtime.QueueRealtimeDestinations;
import com.sourabh.smartqueue.queue.realtime.QueueRealtimeProjectionService;
import com.sourabh.smartqueue.queue.realtime.QueueRealtimeRefreshService;
import com.sourabh.smartqueue.queue.realtime.QueueRealtimeState;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Phase 3D refresh tests: time-driven freshness for live queue snapshots
 * (see ADR-020).
 *
 * <p>Deliberately non-transactional so every join/call/serve commits for
 * real (the AFTER_COMMIT bridge fires into the mocked broker — harmless —
 * while the real projection writes real Redis). Fixtures are unique per
 * test (fresh organization tree, UUID customer emails), so committed rows
 * from other tests never affect per-queue assertions; discovery assertions
 * therefore use contains/not-contains rather than exact counts.
 *
 * <p>Time is fully deterministic: an advanceable project Clock plus
 * backdated {@code joined_at} values (a real join followed by a native
 * timestamp update — no sleeps). The background tick is silenced with a
 * 24-hour interval so only direct {@code refreshQueue}/{@code refreshAll}
 * calls act; scheduling itself is proven by
 * {@code QueueRealtimeRefreshSchedulingTests}.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "queue.realtime.refresh.interval=PT24H")
class QueueRealtimeRefreshTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	static final Instant NOW = Instant.parse("2026-10-06T04:30:00Z");

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

		@Bean
		@Primary
		SimpMessagingTemplate messagingTemplate() {
			return Mockito.mock(SimpMessagingTemplate.class);
		}
	}

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Container
	@ServiceConnection
	static GenericContainer<?> redis =
			new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

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
	QueueEntryLifecycleService lifecycle;

	@Autowired
	QueueRealtimeProjectionService projections;

	@Autowired
	QueueRealtimeRefreshService refreshService;

	@Autowired
	StringRedisTemplate redisTemplate;

	@Autowired
	RedisJson redisJson;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	ManualClock clock;

	@Autowired
	SimpMessagingTemplate messaging;

	private record Fixture(Organization org, Branch branch, Service service,
			ServiceCounter counter, Queue queue) {
	}

	@BeforeEach
	void resetClockAndMock() {
		clock.reset();
		Mockito.reset(messaging);
		TenantContextHolder.clear();
	}

	private Fixture fixture() {
		Organization org = organizations.save(new Organization("Refresh Clinic " + UUID.randomUUID()));
		Branch branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		Service consultation = services.save(
				new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		ServiceCounter counter = counters.save(new ServiceCounter(branch, "Counter 1"));
		capabilities.save(new CounterCapability(branch, consultation, counter));
		Queue queue = queues.save(new Queue(org, branch, consultation));
		TenantContextHolder.set(new TenantContext(org.getId(), branch.getId()));
		return new Fixture(org, branch, consultation, counter, queue);
	}

	private User newCustomer(Organization org) {
		User customer = users.save(new User(org,
				"refresh-" + UUID.randomUUID() + "@example.com", "Refresh Customer"));
		customer.grantRole(RoleType.CUSTOMER);
		return customer;
	}

	private QueueEntry join(Fixture f, QueuePriority priority) {
		QueueEntry entry = joinService.join(
				f.queue().getId(), newCustomer(f.org()).getId(), priority);
		// Pin to the project Clock: the entity defaults joined_at to wall-clock
		// time, which the fixed test Clock is deliberately offset from.
		jdbcTemplate.update("UPDATE queue_entry SET joined_at = ? WHERE id = ?",
				Timestamp.from(clock.instant()), entry.getId());
		return entry;
	}

	/**
	 * Replays an earlier join with exact, deterministic waiting time: a real
	 * join followed by a native timestamp update (no sleeps).
	 */
	private void backdate(UUID entryId, long minutesAgo) {
		jdbcTemplate.update("UPDATE queue_entry SET joined_at = ? WHERE id = ?",
				Timestamp.from(clock.instant().minus(Duration.ofMinutes(minutesAgo))), entryId);
	}

	private String redisKey(Fixture f) {
		return RedisKeyConvention.queueState(
				f.org().getId(), f.branch().getId(), f.queue().getId());
	}

	private QueueRealtimeState storedSnapshot(Fixture f) {
		String raw = redisTemplate.opsForValue().get(redisKey(f));
		assertThat(raw).isNotNull();
		return redisJson.fromJson(raw, QueueRealtimeState.class);
	}

	private String topic(Fixture f) {
		return QueueRealtimeDestinations.topic(
				f.org().getId(), f.branch().getId(), f.queue().getId());
	}

	@Test
	void scheduledRefreshProducesCurrentSnapshot() {
		Fixture f = fixture();
		join(f, QueuePriority.NORMAL);
		join(f, QueuePriority.PRIORITY);
		// The event bridge already projected the joins; start from no stored
		// snapshot so this refresh exercises the changed-state path.
		redisTemplate.delete(redisKey(f));
		clearInvocations(messaging);

		QueueRealtimeRefreshService.Outcome outcome = refreshService.refreshQueue(f.queue().getId());

		assertThat(outcome)
				.isEqualTo(QueueRealtimeRefreshService.Outcome.REFRESHED_BROADCAST);
		QueueRealtimeState snapshot = storedSnapshot(f);
		assertThat(snapshot.queueId()).isEqualTo(f.queue().getId());
		assertThat(snapshot.waitingCount()).isEqualTo(2);
		assertThat(snapshot.waiting().get(0).priority()).isEqualTo(QueuePriority.PRIORITY);
		assertThat(snapshot.waiting().get(0).estimatedWaitMinutes()).isNotNull();

		Long ttl = redisTemplate.getExpire(redisKey(f));
		assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(300);
		verify(messaging, times(1)).convertAndSend(eq(topic(f)), anyString(), anyMap());
	}

	@Test
	void unchangedRefreshRenewsTtlWithoutBroadcast() {
		Fixture f = fixture();
		join(f, QueuePriority.NORMAL);
		redisTemplate.delete(redisKey(f));
		assertThat(refreshService.refreshQueue(f.queue().getId()))
				.isEqualTo(QueueRealtimeRefreshService.Outcome.REFRESHED_BROADCAST);
		clearInvocations(messaging);

		QueueRealtimeRefreshService.Outcome outcome = refreshService.refreshQueue(f.queue().getId());

		assertThat(outcome).isEqualTo(QueueRealtimeRefreshService.Outcome.REFRESHED_QUIET);
		verify(messaging, never()).convertAndSend(anyString(), anyString(), anyMap());
		Long ttl = redisTemplate.getExpire(redisKey(f));
		assertThat(ttl).isNotNull().isPositive();
	}

	@Test
	void noThresholdCrossingKeepsOrderAndWaits() {
		Fixture f = fixture();
		QueueEntry agedNormal = join(f, QueuePriority.NORMAL);
		backdate(agedNormal.getId(), 5);
		join(f, QueuePriority.PRIORITY);
		QueueRealtimeState before = projections.refresh(f.queue().getId());
		assertThat(before.waiting().stream().map(v -> v.tokenNumber()).toList())
				.containsExactly(2, 1);

		clock.advance(Duration.ofMinutes(5));
		clearInvocations(messaging);
		QueueRealtimeRefreshService.Outcome outcome = refreshService.refreshQueue(f.queue().getId());

		assertThat(outcome).isEqualTo(QueueRealtimeRefreshService.Outcome.REFRESHED_QUIET);
		QueueRealtimeState after = storedSnapshot(f);
		assertThat(after.waiting().stream().map(v -> v.tokenNumber()).toList())
				.containsExactly(2, 1);
		assertThat(after.waiting()).isEqualTo(before.waiting());
		verify(messaging, never()).convertAndSend(anyString(), anyString(), anyMap());
	}

	@Test
	void thresholdCrossingChangesRankingAndWaits() {
		Fixture f = fixture();
		QueueEntry agedNormal = join(f, QueuePriority.NORMAL);
		backdate(agedNormal.getId(), 14);
		join(f, QueuePriority.PRIORITY);
		QueueRealtimeState before = projections.refresh(f.queue().getId());
		assertThat(before.waiting().get(0).priority()).isEqualTo(QueuePriority.PRIORITY);
		assertThat(before.waiting().get(1).estimatedWaitMinutes()).isEqualTo(30);

		clock.advance(Duration.ofMinutes(2));
		clearInvocations(messaging);
		QueueRealtimeRefreshService.Outcome outcome = refreshService.refreshQueue(f.queue().getId());

		assertThat(outcome)
				.isEqualTo(QueueRealtimeRefreshService.Outcome.REFRESHED_BROADCAST);
		QueueRealtimeState after = storedSnapshot(f);
		assertThat(after.waiting().stream().map(v -> v.tokenNumber()).toList())
				.containsExactly(1, 2);
		assertThat(after.waiting().get(0).estimatedWaitMinutes()).isZero();
		assertThat(after.waiting().get(1).estimatedWaitMinutes()).isEqualTo(30);
		verify(messaging, times(1)).convertAndSend(eq(topic(f)), anyString(), anyMap());

		assertThat(entries.findById(agedNormal.getId()).orElseThrow().getPriority())
				.isEqualTo(QueuePriority.NORMAL);
		String stored = jdbcTemplate.queryForObject(
				"SELECT priority FROM queue_entry WHERE id = ?", String.class, agedNormal.getId());
		assertThat(stored).isEqualTo("NORMAL");
	}

	@Test
	void agingCapRespectedAcrossRefresh() {
		Fixture f = fixture();
		QueueEntry ancientNormal = join(f, QueuePriority.NORMAL);
		backdate(ancientNormal.getId(), 600);
		QueueEntry waitedPriority = join(f, QueuePriority.PRIORITY);
		backdate(waitedPriority.getId(), 60);

		QueueRealtimeState before = projections.refresh(f.queue().getId());
		assertThat(before.waiting().get(0).priority()).isEqualTo(QueuePriority.PRIORITY);

		clock.advance(Duration.ofMinutes(30));
		refreshService.refreshQueue(f.queue().getId());

		QueueRealtimeState after = storedSnapshot(f);
		assertThat(after.waiting().get(0).priority()).isEqualTo(QueuePriority.PRIORITY);
	}

	@Test
	void emergencyStaysAheadAcrossRefresh() {
		Fixture f = fixture();
		QueueEntry ancientNormal = join(f, QueuePriority.NORMAL);
		backdate(ancientNormal.getId(), 600);
		join(f, QueuePriority.EMERGENCY);

		projections.refresh(f.queue().getId());
		clock.advance(Duration.ofMinutes(60));
		refreshService.refreshQueue(f.queue().getId());

		QueueRealtimeState after = storedSnapshot(f);
		assertThat(after.waiting().get(0).priority()).isEqualTo(QueuePriority.EMERGENCY);
		assertThat(after.waitingCount()).isEqualTo(2);
	}

	@Test
	void zeroCapacityYieldsNullWaits() {
		Fixture f = fixture();
		Service physio = services.save(new Service(f.branch(), "Physio", Duration.ofMinutes(20)));
		Queue physioQueue = queues.save(new Queue(f.org(), f.branch(), physio));
		QueueEntry physioEntry = joinService.join(
				physioQueue.getId(), newCustomer(f.org()).getId(), QueuePriority.NORMAL);
		jdbcTemplate.update("UPDATE queue_entry SET joined_at = ? WHERE id = ?",
				Timestamp.from(clock.instant()), physioEntry.getId());
		String physioKey = RedisKeyConvention.queueState(
				f.org().getId(), f.branch().getId(), physioQueue.getId());
		redisTemplate.delete(physioKey);
		clearInvocations(messaging);

		QueueRealtimeRefreshService.Outcome outcome =
				refreshService.refreshQueue(physioQueue.getId());

		assertThat(outcome)
				.isEqualTo(QueueRealtimeRefreshService.Outcome.REFRESHED_BROADCAST);
		String raw = redisTemplate.opsForValue().get(physioKey);
		QueueRealtimeState snapshot = redisJson.fromJson(raw, QueueRealtimeState.class);
		assertThat(snapshot.waitingCount()).isEqualTo(1);
		assertThat(snapshot.waiting().get(0).estimatedWaitMinutes()).isNull();
	}

	@Test
	void multipleCountersShareLoad() {
		Fixture f = fixture();
		ServiceCounter counter2 = counters.save(new ServiceCounter(f.branch(), "Counter 2"));
		capabilities.save(new CounterCapability(f.branch(), f.service(), counter2));
		join(f, QueuePriority.NORMAL);
		join(f, QueuePriority.NORMAL);
		join(f, QueuePriority.NORMAL);
		clearInvocations(messaging);

		refreshService.refreshQueue(f.queue().getId());

		QueueRealtimeState snapshot = storedSnapshot(f);
		assertThat(snapshot.waiting().stream()
				.map(v -> v.estimatedWaitMinutes()).toList())
				.containsExactly(0, 0, 30);
	}

	@Test
	void calledAndServingTokensListedWithActiveSessionWait() {
		Fixture f = fixture();
		QueueEntry first = join(f, QueuePriority.NORMAL);
		backdate(first.getId(), 1);
		QueueEntry second = join(f, QueuePriority.NORMAL);
		QueueEntry called = callService.callNext(f.counter().getId());
		lifecycle.startServing(called.getId(), f.counter().getId());
		clearInvocations(messaging);

		refreshService.refreshQueue(f.queue().getId());

		QueueRealtimeState snapshot = storedSnapshot(f);
		assertThat(snapshot.waitingCount()).isEqualTo(1);
		assertThat(called.getTokenNumber()).isEqualTo(first.getTokenNumber());
		assertThat(snapshot.waiting().get(0).tokenNumber()).isEqualTo(second.getTokenNumber());
		assertThat(snapshot.servingTokens()).containsExactly(called.getTokenNumber());
		assertThat(snapshot.calledCount()).isZero();
		assertThat(snapshot.waiting().get(0).estimatedWaitMinutes()).isEqualTo(30);
	}

	@Test
	void terminalEntriesExcluded() {
		Fixture f = fixture();
		QueueEntry entry = join(f, QueuePriority.NORMAL);
		lifecycle.cancel(entry.getId());
		clearInvocations(messaging);

		refreshService.refreshQueue(f.queue().getId());

		QueueRealtimeState snapshot = storedSnapshot(f);
		assertThat(snapshot.waitingCount()).isZero();
		assertThat(snapshot.waiting()).isEmpty();

		QueueRealtimeRefreshService.RefreshSummary summary = refreshService.refreshAll();
		assertThat(summary.considered()).doesNotContain(f.queue().getId());
	}

	@Test
	void emptyAndInactiveQueuesNotDiscovered() {
		Fixture live = fixture();
		join(live, QueuePriority.NORMAL);

		Fixture empty = fixture();

		Fixture retired = fixture();
		join(retired, QueuePriority.NORMAL);
		retired.queue().setStatus(QueueStatus.INACTIVE);
		queues.save(retired.queue());
		redisTemplate.delete(RedisKeyConvention.queueState(
				retired.org().getId(), retired.branch().getId(), retired.queue().getId()));
		clearInvocations(messaging);

		QueueRealtimeRefreshService.RefreshSummary summary = refreshService.refreshAll();

		assertThat(summary.considered()).contains(live.queue().getId());
		assertThat(summary.considered())
				.doesNotContain(empty.queue().getId(), retired.queue().getId());
		assertThat(summary.refreshed()).contains(live.queue().getId());
		assertThat(redisTemplate.opsForValue().get(redisKey(live))).isNotNull();
		assertThat(redisTemplate.opsForValue().get(redisKey(empty))).isNull();
		assertThat(redisTemplate.opsForValue().get(RedisKeyConvention.queueState(
				retired.org().getId(), retired.branch().getId(),
				retired.queue().getId()))).isNull();
	}

	@Test
	void eventThenScheduledDoesNotRegress() {
		Fixture f = fixture();
		join(f, QueuePriority.NORMAL);
		projections.refresh(f.queue().getId());

		clock.advance(Duration.ofMinutes(20));
		join(f, QueuePriority.PRIORITY);
		projections.refresh(f.queue().getId());
		clearInvocations(messaging);

		QueueRealtimeRefreshService.Outcome outcome = refreshService.refreshQueue(f.queue().getId());

		assertThat(outcome).isEqualTo(QueueRealtimeRefreshService.Outcome.REFRESHED_QUIET);
		QueueRealtimeState snapshot = storedSnapshot(f);
		assertThat(snapshot.waitingCount()).isEqualTo(2);
		assertThat(snapshot.waiting().get(0).priority()).isEqualTo(QueuePriority.NORMAL);
		verify(messaging, never()).convertAndSend(anyString(), anyString(), anyMap());
	}

	@Test
	void staleSnapshotCannotOverwriteNewer() {
		Fixture f = fixture();
		join(f, QueuePriority.NORMAL);
		projections.refresh(f.queue().getId());

		String previousRaw = projections.readRawSnapshot(f.queue().getId());
		QueueRealtimeState stale = projections.buildSnapshot(f.queue().getId());

		join(f, QueuePriority.PRIORITY);
		QueueRealtimeState newer = projections.refresh(f.queue().getId());
		assertThat(newer.waitingCount()).isEqualTo(2);

		assertThat(projections.compareAndStore(stale, previousRaw)).isFalse();

		QueueRealtimeState current = storedSnapshot(f);
		assertThat(QueueRealtimeState.sameLiveContent(current, newer)).isTrue();
		assertThat(current.waitingCount()).isEqualTo(2);

		clearInvocations(messaging);
		QueueRealtimeRefreshService.Outcome outcome = refreshService.refreshQueue(f.queue().getId());
		assertThat(outcome).isEqualTo(QueueRealtimeRefreshService.Outcome.REFRESHED_QUIET);
		assertThat(storedSnapshot(f).waitingCount()).isEqualTo(2);
	}

	@Test
	void messagingFailureKeepsRedisAndPostgres() {
		Fixture f = fixture();
		QueueEntry entry = join(f, QueuePriority.NORMAL);
		clearInvocations(messaging);
		doThrow(new RuntimeException("broker down"))
				.when(messaging).convertAndSend(anyString(), anyString(), anyMap());

		QueueRealtimeRefreshService.Outcome outcome = refreshService.refreshQueue(f.queue().getId());

		assertThat(outcome).isEqualTo(QueueRealtimeRefreshService.Outcome.REFRESHED_QUIET);
		assertThat(storedSnapshot(f).waitingCount()).isEqualTo(1);
		assertThat(entries.findById(entry.getId())).isPresent();
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}
}
