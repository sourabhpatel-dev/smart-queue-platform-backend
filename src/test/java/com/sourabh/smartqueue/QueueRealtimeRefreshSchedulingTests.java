package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import com.sourabh.smartqueue.infrastructure.redis.RedisKeyConvention;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueJoinService;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.realtime.QueueRealtimeRefreshService;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Proves the scheduled tick is actually wired (see ADR-020): with a
 * sub-second interval, an active queue's deleted snapshot reappears without
 * any business event occurring in between. Bounded polling synchronizes on
 * the key — never wall-clock sleeps for time passage. Most refresh behavior
 * stays in {@code QueueRealtimeRefreshTests} with a silenced scheduler, so
 * it remains deterministic.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "queue.realtime.refresh.interval=PT0.1S")
class QueueRealtimeRefreshSchedulingTests {

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
	QueueRealtimeRefreshService refreshService;

	@Autowired
	StringRedisTemplate redisTemplate;

	@Test
	void schedulerBeanIsRegisteredWhenEnabled() {
		assertThat(refreshService).isNotNull();
	}

	@Test
	void schedulerRecreatesDeletedSnapshotWithoutEvents() throws Exception {
		Organization org = organizations.save(new Organization("Tick Clinic " + UUID.randomUUID()));
		Branch branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		Service consultation = services.save(
				new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		ServiceCounter counter = counters.save(new ServiceCounter(branch, "Counter 1"));
		capabilities.save(new CounterCapability(branch, consultation, counter));
		Queue queue = queues.save(new Queue(org, branch, consultation));
		User customer = users.save(new User(org,
				"tick-" + UUID.randomUUID() + "@example.com", "Tick Customer"));
		customer.grantRole(RoleType.CUSTOMER);
		joinService.join(queue.getId(), customer.getId(), QueuePriority.NORMAL);

		String key = RedisKeyConvention.queueState(org.getId(), branch.getId(), queue.getId());
		redisTemplate.delete(key);

		// No business event occurs from here on: only the tick may rebuild.
		long deadline = System.currentTimeMillis() + 15_000;
		String recreated = null;
		while (System.currentTimeMillis() < deadline) {
			recreated = redisTemplate.opsForValue().get(key);
			if (recreated != null && recreated.contains("\"waitingCount\":1")) {
				break;
			}
			Thread.sleep(100);
		}
		assertThat(recreated).isNotNull().contains("\"waitingCount\":1");
	}

	@Test
	void schedulerLeavesEmptyQueuesAlone() throws Exception {
		Organization org = organizations.save(new Organization("Idle Clinic " + UUID.randomUUID()));
		Branch branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		Service consultation = services.save(
				new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		Queue queue = queues.save(new Queue(org, branch, consultation));

		String key = RedisKeyConvention.queueState(org.getId(), branch.getId(), queue.getId());

		// Bounded absence proof: at a 100 ms interval the tick had ample
		// chances to write, yet an empty queue holds no live state to refresh.
		Thread.sleep(1_000);
		assertThat(redisTemplate.opsForValue().get(key)).isNull();
	}
}
