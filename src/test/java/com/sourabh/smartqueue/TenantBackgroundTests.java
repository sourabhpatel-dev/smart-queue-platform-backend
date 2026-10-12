package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Background task tests (see ADR-019): prove that the scheduled refresh
 * and event-driven projection work without a pre-set HTTP tenant context.
 * The scheduler discovers queues globally and sets its own per-queue
 * context; the event bridge sets its own context from the event payload.
 * Neither depends on a thread-local that was never set for them.
 *
 * <p>Non-transactional so business commits (and their AFTER_COMMIT
 * broadcasts) really happen. Own containers per test class.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "queue.realtime.refresh.interval=PT24H")
class TenantBackgroundTests {

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

	@BeforeEach
	void ensureNoContext() {
		TenantContextHolder.clear();
	}

	@Test
	void scheduledRefreshWorksWithoutHttpContext() {
		Organization org = organizations.save(new Organization("BgOrg " + UUID.randomUUID()));
		Branch branch = branches.save(new Branch(org, "BgBranch", "City", "Country", KOLKATA));
		Service service = services.save(new Service(branch, "BgService", Duration.ofMinutes(30)));
		ServiceCounter counter = counters.save(new ServiceCounter(branch, "BgCounter"));
		capabilities.save(new CounterCapability(branch, service, counter));
		Queue queue = queues.save(new Queue(org, branch, service));
		User customer = users.save(new User(org,
				"bg-" + UUID.randomUUID() + "@example.com", "BgCustomer"));
		customer.grantRole(RoleType.CUSTOMER);
		joinService.join(queue.getId(), customer.getId(), QueuePriority.NORMAL);

		String key = RedisKeyConvention.queueState(org.getId(), branch.getId(), queue.getId());
		redisTemplate.delete(key);

		QueueRealtimeRefreshService.RefreshSummary summary = refreshService.refreshAll();

		assertThat(summary.considered()).contains(queue.getId());
		assertThat(summary.refreshed()).contains(queue.getId());
		assertThat(redisTemplate.opsForValue().get(key)).isNotNull();
		assertThat(TenantContextHolder.getOrNull()).isNull();
	}

	@Test
	void eventBridgeWorksWithoutHttpContext() {
		Organization org = organizations.save(new Organization("EvOrg " + UUID.randomUUID()));
		Branch branch = branches.save(new Branch(org, "EvBranch", "City", "Country", KOLKATA));
		Service service = services.save(new Service(branch, "EvService", Duration.ofMinutes(30)));
		ServiceCounter counter = counters.save(new ServiceCounter(branch, "EvCounter"));
		capabilities.save(new CounterCapability(branch, service, counter));
		Queue queue = queues.save(new Queue(org, branch, service));
		User customer = users.save(new User(org,
				"ev-" + UUID.randomUUID() + "@example.com", "EvCustomer"));
		customer.grantRole(RoleType.CUSTOMER);

		joinService.join(queue.getId(), customer.getId(), QueuePriority.NORMAL);

		String key = RedisKeyConvention.queueState(org.getId(), branch.getId(), queue.getId());
		assertThat(redisTemplate.opsForValue().get(key)).isNotNull();
		assertThat(TenantContextHolder.getOrNull()).isNull();
	}

	@Test
	void multipleTenantsRefreshedWithoutContext() {
		Organization org1 = organizations.save(new Organization("Multi1 " + UUID.randomUUID()));
		Branch branch1 = branches.save(new Branch(org1, "B1", "City", "Country", KOLKATA));
		Service service1 = services.save(new Service(branch1, "S1", Duration.ofMinutes(30)));
		Queue queue1 = queues.save(new Queue(org1, branch1, service1));
		User customer1 = users.save(new User(org1,
				"m1-" + UUID.randomUUID() + "@example.com", "M1"));
		customer1.grantRole(RoleType.CUSTOMER);
		joinService.join(queue1.getId(), customer1.getId(), QueuePriority.NORMAL);

		Organization org2 = organizations.save(new Organization("Multi2 " + UUID.randomUUID()));
		Branch branch2 = branches.save(new Branch(org2, "B2", "City", "Country", KOLKATA));
		Service service2 = services.save(new Service(branch2, "S2", Duration.ofMinutes(30)));
		Queue queue2 = queues.save(new Queue(org2, branch2, service2));
		User customer2 = users.save(new User(org2,
				"m2-" + UUID.randomUUID() + "@example.com", "M2"));
		customer2.grantRole(RoleType.CUSTOMER);
		joinService.join(queue2.getId(), customer2.getId(), QueuePriority.NORMAL);

		redisTemplate.delete(RedisKeyConvention.queueState(org1.getId(), branch1.getId(), queue1.getId()));
		redisTemplate.delete(RedisKeyConvention.queueState(org2.getId(), branch2.getId(), queue2.getId()));

		QueueRealtimeRefreshService.RefreshSummary summary = refreshService.refreshAll();

		assertThat(summary.considered()).contains(queue1.getId(), queue2.getId());
		assertThat(summary.refreshed()).contains(queue1.getId(), queue2.getId());
		assertThat(redisTemplate.opsForValue().get(
				RedisKeyConvention.queueState(org1.getId(), branch1.getId(), queue1.getId()))).isNotNull();
		assertThat(redisTemplate.opsForValue().get(
				RedisKeyConvention.queueState(org2.getId(), branch2.getId(), queue2.getId()))).isNotNull();
		assertThat(TenantContextHolder.getOrNull()).isNull();
	}
}
