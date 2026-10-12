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
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueCallService;
import com.sourabh.smartqueue.queue.QueueEntryLifecycleService;
import com.sourabh.smartqueue.queue.QueueEntryRepository;
import com.sourabh.smartqueue.queue.QueueJoinService;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.realtime.QueueRealtimeProjectionService;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Proves the Phase 3C degradation contract (ADR-018): with Redis down, the
 * AFTER_COMMIT projection fails while committed queue transactions still
 * succeed. Deliberately not {@code @Transactional} — the commit must really
 * happen for the listener to fire.
 */
@SpringBootTest
@Testcontainers
class QueueRealtimeFailureTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Container
	static GenericContainer<?> redis =
			new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

	/**
	 * {@code @ServiceConnection} is intentionally not used for Redis: the
	 * container is stopped mid-test, so its port must be resolved before the
	 * application context starts.
	 */
	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", () -> redis.getHost());
		registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
	}

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
	QueueEntryRepository entries;

	@Autowired
	QueueJoinService joinService;

	@Autowired
	QueueCallService callService;

	@Autowired
	QueueEntryLifecycleService lifecycle;

	@Autowired
	QueueRealtimeProjectionService projections;

	Organization org;
	Branch branch;
	Service consultation;
	ServiceCounter counter1;
	Queue queue;

	@BeforeEach
	void setUp() {
		org = organizations.save(new Organization("Resilient Clinic " + UUID.randomUUID()));
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
		User customer = users.save(new User(org,
				"degraded-" + UUID.randomUUID() + "@example.com", "Degraded Customer"));
		customer.grantRole(RoleType.CUSTOMER);
		return customer;
	}

	@Test
	void redisOutageDoesNotFailQueueTransactions() {
		redis.stop();

		var joined = joinService.join(queue.getId(), newCustomer().getId(), QueuePriority.NORMAL);
		var called = callService.callNext(counter1.getId());
		var serving = lifecycle.startServing(called.getId(), counter1.getId());
		var completed = lifecycle.complete(serving.getId());

		assertThat(joined.getTokenNumber()).isEqualTo(1);
		assertThat(completed.getStatus().name()).isEqualTo("COMPLETED");
		assertThat(entries.findById(completed.getId())).isPresent();

		assertThatThrownBy(() -> projections.refresh(queue.getId()))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	void redisOutageStillCancelsTerminalEntries() {
		redis.stop();

		var joined = joinService.join(queue.getId(), newCustomer().getId(), QueuePriority.PRIORITY);
		var cancelled = lifecycle.cancel(joined.getId());

		assertThat(cancelled.getStatus().name()).isEqualTo("CANCELLED");
		assertThatThrownBy(() -> projections.refresh(queue.getId()))
				.isInstanceOf(DataAccessException.class);
	}
}
