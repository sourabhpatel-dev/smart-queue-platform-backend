package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.counter.CounterCapability;
import com.sourabh.smartqueue.counter.CounterCapabilityRepository;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.staff.StaffAssignment;
import com.sourabh.smartqueue.staff.StaffAssignmentRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Proves the queue REST adapter never depends on Redis (see ADR-027):
 * with the Redis container stopped mid-test, joining, reading state,
 * and claiming all still succeed from PostgreSQL — only the derived
 * realtime projection degrades, and REST never reads it.
 *
 * <p>Uses {@code @DynamicPropertySource} (not {@code @ServiceConnection})
 * for Redis, like {@code QueueRealtimeFailureTests}: the container is
 * stopped mid-test, so its port must resolve before the context starts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class QueueRestDegradationTests {

	static final String PASSWORD = "Queue-Degrade-16";
	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Container
	static GenericContainer<?> redis =
			new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", () -> redis.getHost());
		registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
	}

	@LocalServerPort
	int port;

	@Autowired
	OrganizationRepository organizations;

	@Autowired
	BranchRepository branches;

	@Autowired
	ServiceRepository services;

	@Autowired
	QueueRepository queues;

	@Autowired
	ServiceCounterRepository counters;

	@Autowired
	CounterCapabilityRepository capabilities;

	@Autowired
	UserRepository users;

	@Autowired
	OrganizationMembershipRepository memberships;

	@Autowired
	StaffAssignmentRepository assignments;

	@Autowired
	AuthService authService;

	@Autowired
	PasswordCredentialService credentials;

	@Autowired
	ObjectMapper objectMapper;

	private final HttpClient http = HttpClient.newHttpClient();

	String managerToken;
	String staffToken;
	Organization org;
	Branch branch;
	Queue queue;
	ServiceCounter counter;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		org = organizations.save(new Organization("Degrade Org " + tag));
		branch = branches.save(new Branch(org, "Downtown " + tag,
				"Indore", "India", KOLKATA));
		Service svc = services.save(new Service(branch, "Consult " + tag,
				Duration.ofMinutes(30)));
		queue = queues.save(new Queue(org, branch, svc));
		counter = counters.save(new ServiceCounter(branch, "Counter 1 " + tag));
		// Direct construction is safe here: every association was
		// materialized at build time, so no lazy proxy needs a session.
		capabilities.save(new CounterCapability(branch, svc, counter));

		User manager = users.save(new User(org, "mgr-" + tag + "@example.com", "Mgr"));
		manager.grantRole(RoleType.MANAGER);
		users.save(manager);
		memberships.save(new OrganizationMembership(manager, org, MembershipRole.MANAGER));
		credentials.setPassword(manager.getId(), PASSWORD);
		managerToken = authService.login(manager.getEmail(), PASSWORD).token();

		User staff = users.save(new User(org, "staff-" + tag + "@example.com", "Stf"));
		staff.grantRole(RoleType.STAFF);
		users.save(staff);
		memberships.save(new OrganizationMembership(staff, org, MembershipRole.STAFF));
		credentials.setPassword(staff.getId(), PASSWORD);
		assignments.save(new StaffAssignment(org, staff, branch));
		staffToken = authService.login(staff.getEmail(), PASSWORD).token();
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
		if (!redis.isRunning()) {
			redis.start();
		}
	}

	private HttpResponse<String> post(String path, String body, String token) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path))
				.GET();
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void queueRestWorksWithRedisDown() throws Exception {
		redis.stop();
		try {
			// Join from PostgreSQL state alone.
			HttpResponse<String> joined = post(
					"/api/v1/branches/" + branch.getId() + "/queues/" + queue.getId()
							+ "/entries",
					"{}", staffToken);
			assertThat(joined.statusCode()).isEqualTo(HttpStatus.CREATED.value());

			// State rebuilt from PostgreSQL, not from any snapshot.
			assertThat(get("/api/v1/queues/" + queue.getId(), managerToken)
					.statusCode()).isEqualTo(HttpStatus.OK.value());

			// Atomic claim from PostgreSQL locks alone.
			assertThat(post("/api/v1/counters/" + counter.getId() + "/call-next", null,
					staffToken).statusCode()).isEqualTo(HttpStatus.OK.value());
		} finally {
			redis.start();
		}
	}
}




