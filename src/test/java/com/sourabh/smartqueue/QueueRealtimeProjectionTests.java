package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
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
import com.sourabh.smartqueue.queue.InvalidQueueRequestException;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueCallService;
import com.sourabh.smartqueue.queue.QueueEntryLifecycleService;
import com.sourabh.smartqueue.queue.QueueEntryStatus;
import com.sourabh.smartqueue.queue.QueueJoinService;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.realtime.QueueRealtimeProjectionService;
import com.sourabh.smartqueue.queue.realtime.QueueRealtimeState;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Phase 3C projection tests: snapshots derive from PostgreSQL and land in
 * Redis (see ADR-018). Each test rolls back (Redis keys are unique per
 * queue, so nothing leaks between tests). Own containers per test class.
 */
@SpringBootTest
@Testcontainers
@Transactional
class QueueRealtimeProjectionTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

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
	StringRedisTemplate redisTemplate;

	@Autowired
	RedisJson redisJson;

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
				"rts" + customerSequence + "@example.com", "Rts " + customerSequence));
		customer.grantRole(RoleType.CUSTOMER);
		return customer;
	}

	private QueueRealtimeState storedSnapshot() {
		String key = RedisKeyConvention.queueState(org.getId(), branch.getId(), queue.getId());
		String json = redisTemplate.opsForValue().get(key);
		assertThat(json).isNotNull();
		return redisJson.fromJson(json, QueueRealtimeState.class);
	}

	@Test
	void refreshWritesSnapshotToRedis() {
		joinService.join(queue.getId(), newCustomer().getId(), QueuePriority.NORMAL);
		joinService.join(queue.getId(), newCustomer().getId(), QueuePriority.PRIORITY);

		QueueRealtimeState snapshot = projections.refresh(queue.getId());

		assertThat(snapshot.organizationId()).isEqualTo(org.getId());
		assertThat(snapshot.branchId()).isEqualTo(branch.getId());
		assertThat(snapshot.queueId()).isEqualTo(queue.getId());
		assertThat(snapshot.serviceId()).isEqualTo(consultation.getId());
		assertThat(snapshot.waitingCount()).isEqualTo(2);
		assertThat(snapshot.waiting()).hasSize(2);
		// PRIORITY first by shared rank order, each carrying its wait.
		assertThat(snapshot.waiting().get(0).priority()).isEqualTo(QueuePriority.PRIORITY);
		assertThat(snapshot.waiting().get(0).tokenNumber()).isEqualTo(2);
		assertThat(snapshot.waiting().get(1).tokenNumber()).isEqualTo(1);
		assertThat(snapshot.waiting().get(0).estimatedWaitMinutes()).isNotNull();
		assertThat(snapshot.generatedAt()).isNotNull();

		// Same snapshot is what Redis holds.
		QueueRealtimeState stored = storedSnapshot();
		assertThat(stored.queueId()).isEqualTo(queue.getId());
		assertThat(stored.waitingCount()).isEqualTo(2);
	}

	@Test
	void snapshotContainsNoCustomerData() {
		User customer = newCustomer();
		joinService.join(queue.getId(), customer.getId(), QueuePriority.NORMAL);
		projections.refresh(queue.getId());

		String key = RedisKeyConvention.queueState(org.getId(), branch.getId(), queue.getId());
		String json = redisTemplate.opsForValue().get(key);

		assertThat(json)
				.doesNotContain(customer.getId().toString())
				.doesNotContain(customer.getEmail())
				.contains("\"tokenNumber\":1");
	}

	@Test
	void servingAndCalledTokensAreListed() {
		joinService.join(queue.getId(), newCustomer().getId(), QueuePriority.NORMAL);
		var waiting = joinService.join(queue.getId(), newCustomer().getId(), QueuePriority.NORMAL);
		var serving = callService.callNext(counter1.getId());
		lifecycle.startServing(serving.getId(), counter1.getId());

		QueueRealtimeState snapshot = projections.refresh(queue.getId());

		assertThat(snapshot.waitingCount()).isEqualTo(1);
		assertThat(snapshot.waiting().get(0).tokenNumber()).isEqualTo(waiting.getTokenNumber());
		assertThat(snapshot.servingCount()).isEqualTo(1);
		assertThat(snapshot.servingTokens()).containsExactly(serving.getTokenNumber());
		assertThat(snapshot.calledCount()).isZero();
	}

	@Test
	void calledTokensAreListed() {
		joinService.join(queue.getId(), newCustomer().getId(), QueuePriority.NORMAL);
		var called = callService.callNext(counter1.getId());

		QueueRealtimeState snapshot = projections.refresh(queue.getId());

		assertThat(snapshot.calledCount()).isEqualTo(1);
		assertThat(snapshot.calledTokens()).containsExactly(called.getTokenNumber());
		assertThat(snapshot.servingCount()).isZero();
	}

	@Test
	void terminalEntriesLeaveTheSnapshot() {
		var entry = joinService.join(queue.getId(), newCustomer().getId(), QueuePriority.NORMAL);
		lifecycle.cancel(entry.getId());

		QueueRealtimeState snapshot = projections.refresh(queue.getId());

		assertThat(snapshot.waitingCount()).isZero();
		assertThat(snapshot.waiting()).isEmpty();
	}

	@Test
	void rebuildRecreatesDeletedSnapshot() {
		joinService.join(queue.getId(), newCustomer().getId(), QueuePriority.NORMAL);
		QueueRealtimeState first = projections.refresh(queue.getId());
		redisTemplate.delete(RedisKeyConvention.queueState(
				org.getId(), branch.getId(), queue.getId()));

		QueueRealtimeState rebuilt = projections.refresh(queue.getId());

		assertThat(rebuilt.waitingCount()).isEqualTo(first.waitingCount());
		assertThat(rebuilt.waiting()).isEqualTo(first.waiting());
		assertThat(rebuilt.queueId()).isEqualTo(first.queueId());
	}

	@Test
	void doubleRefreshIsIdempotent() {
		joinService.join(queue.getId(), newCustomer().getId(), QueuePriority.NORMAL);

		QueueRealtimeState first = projections.refresh(queue.getId());
		QueueRealtimeState second = projections.refresh(queue.getId());

		assertThat(second.waiting()).isEqualTo(first.waiting());
		assertThat(second.waitingCount()).isEqualTo(first.waitingCount());
	}

	@Test
	void snapshotHasExplicitTtl() {
		joinService.join(queue.getId(), newCustomer().getId(), QueuePriority.NORMAL);
		projections.refresh(queue.getId());

		Long ttl = redisTemplate.getExpire(RedisKeyConvention.queueState(
				org.getId(), branch.getId(), queue.getId()));
		assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(300);
	}

	@Test
	void unknownQueueIsRejected() {
		assertThatThrownBy(() -> projections.refresh(UUID.randomUUID()))
				.isInstanceOf(InvalidQueueRequestException.class);
	}

	@Test
	void flywaySchemaUnchanged() {
		Integer tables = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables"
						+ " WHERE table_schema = 'public'"
						+ " AND table_name IN ('queue', 'queue_entry', 'queue_token_counter',"
						+ " 'service_session', 'appointment')",
				Integer.class);
		assertThat(tables).isEqualTo(5);
	}
}
