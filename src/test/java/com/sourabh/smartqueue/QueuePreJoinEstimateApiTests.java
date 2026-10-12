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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.counter.CounterCapability;
import com.sourabh.smartqueue.counter.CounterCapabilityRepository;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.QueueStatus;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.service.ServiceStatus;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class QueuePreJoinEstimateApiTests {

	static final String PASSWORD = "Est-Api-22";
	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Container
	@ServiceConnection
	static GenericContainer<?> redis =
			new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

	@LocalServerPort
	int port;

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
	OrganizationMembershipRepository memberships;

	@Autowired
	AuthService authService;

	@Autowired
	PasswordCredentialService credentials;

	@Autowired
	ObjectMapper objectMapper;

	private final HttpClient http = HttpClient.newHttpClient();

	String tag;
	Organization orgA;
	Organization orgB;
	Branch branchA1;
	Branch branchA2;
	Branch branchB1;
	Service svcA1;
	Service svcA2;
	Service svcB1;
	Queue queueA1;
	User customerA1;
	User customerB1;
	String customerA1Token;
	String customerB1Token;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		tag = UUID.randomUUID().toString().substring(0, 8);
		orgA = organizations.save(new Organization("Est Api Org A " + tag));
		orgB = organizations.save(new Organization("Est Api Org B " + tag));
		branchA1 = branches.save(new Branch(orgA, "Downtown " + tag, "Indore", "India", KOLKATA));
		branchA2 = branches.save(new Branch(orgA, "Uptown " + tag, "Bhopal", "India", KOLKATA));
		branchB1 = branches.save(new Branch(orgB, "Riverside " + tag, "Pune", "India", KOLKATA));

		svcA1 = services.save(new Service(branchA1, "Consult " + tag, Duration.ofMinutes(30)));
		svcA2 = services.save(new Service(branchA2, "Xray " + tag, Duration.ofMinutes(15)));
		svcB1 = services.save(new Service(branchB1, "Consult " + tag, Duration.ofMinutes(30)));

		ServiceCounter counterA1 = counters.save(new ServiceCounter(branchA1, "Counter 1"));
		capabilities.save(new CounterCapability(branchA1, svcA1, counterA1));

		queueA1 = queues.save(new Queue(orgA, branchA1, svcA1));

		customerA1 = newUser(orgA, "cust-a1-" + tag, RoleType.CUSTOMER);
		customerB1 = newUser(orgB, "cust-b1-" + tag, RoleType.CUSTOMER);

		customerA1Token = tokenFor(customerA1);
		customerB1Token = tokenFor(customerB1);
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private User newUser(Organization org, String local, RoleType role) {
		User user = users.save(new User(org, local + "@example.com", local));
		user.grantRole(role);
		user = users.save(user);
		grantMembership(user, org, role);
		credentials.setPassword(user.getId(), PASSWORD);
		return user;
	}

	/**
	 * Mirrors the V20 backfill for fixtures (ADR-040): operational roles
	 * carry an ACTIVE membership at the fixture organization — the row the
	 * authorization boundary actually checks.
	 */
	private void grantMembership(User user, Organization org, RoleType role) {
		if (role == RoleType.CUSTOMER) {
			return;
		}
		memberships.save(new OrganizationMembership(user, org,
				MembershipRole.valueOf(role.name())));
	}

	private String tokenFor(User user) {
		return authService.login(user.getEmail(), PASSWORD).token();
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

	private JsonNode json(String body) {
		try {
			return objectMapper.readTree(body);
		} catch (tools.jackson.core.JacksonException e) {
			throw new IllegalStateException("unreadable JSON response: " + body, e);
		}
	}

	private String estimatePath(UUID branchId, UUID serviceId) {
		return "/api/v1/branches/" + branchId + "/queues/" + serviceId + "/estimate-join";
	}

	@Test
	void successReturnsAggregateEstimate() throws Exception {
		HttpResponse<String> response = get(estimatePath(branchA1.getId(), svcA1.getId()), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(body.get("queueId").asText()).isEqualTo(queueA1.getId().toString());
		assertThat(body.get("waitingCount").asInt()).isZero();
		assertThat(body.get("estimatedWaitMinutes").asInt()).isZero();
		assertThat(body.get("capableCounterCount").asInt()).isEqualTo(1);
		assertThat(body.get("activeSessionCount").asInt()).isZero();
		assertThat(body.get("calculatedAt")).isNotNull();
	}

	@Test
	void inactiveQueueReturns400() throws Exception {
		queueA1.setStatus(QueueStatus.INACTIVE);
		queues.save(queueA1);

		HttpResponse<String> response = get(estimatePath(branchA1.getId(), svcA1.getId()), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(json(response.body()).get("code").asText()).isEqualTo("QUEUE_INACTIVE");
	}

	@Test
	void inactiveServiceReturns400() throws Exception {
		svcA1.setStatus(ServiceStatus.INACTIVE);
		services.save(svcA1);

		HttpResponse<String> response = get(estimatePath(branchA1.getId(), svcA1.getId()), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(json(response.body()).get("code").asText()).isEqualTo("SERVICE_INACTIVE");
	}

	@Test
	void customerFromAnotherOrganizationReadsEstimate() throws Exception {
		// ADR-040: a platform-level customer belongs to no organization —
		// the aggregate estimate at any ACTIVE branch is readable.
		HttpResponse<String> response = get(estimatePath(branchA1.getId(), svcA1.getId()), customerB1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(response.body()).get("queueId").asString())
				.isEqualTo(queueA1.getId().toString());
	}

	@Test
	void missingQueueReturns404() throws Exception {
		// A service in the branch with no queue row for the pair: the
		// estimate endpoint has nothing to estimate (404 QUEUE_NOT_FOUND).
		// (The old setup name-collided with serviceFromAnotherBranch below
		// by reusing svcA2 from another branch — that pair is a 422, not
		// a missing queue.)
		com.sourabh.smartqueue.service.Service orphan = services.save(
				new Service(branchA1,
						"Orphan " + UUID.randomUUID(), Duration.ofMinutes(30)));
		HttpResponse<String> response = get(estimatePath(branchA1.getId(), orphan.getId()), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(json(response.body()).get("code").asText()).isEqualTo("QUEUE_NOT_FOUND");
	}

	@Test
	void serviceFromAnotherBranchReturns422() throws Exception {
		HttpResponse<String> response = get(estimatePath(branchA1.getId(), svcA2.getId()), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
	}

	@Test
	void unauthenticatedRequestReturns401() throws Exception {
		HttpResponse<String> response = get(estimatePath(branchA1.getId(), svcA1.getId()), null);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	@Test
	void responseDoesNotContainCustomerPii() throws Exception {
		HttpResponse<String> response = get(estimatePath(branchA1.getId(), svcA1.getId()), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		String body = response.body();
		assertThat(body).doesNotContain("customerId");
		assertThat(body).doesNotContain("customerName");
		assertThat(body).doesNotContain("email");
		assertThat(body).doesNotContain("phone");
		assertThat(body).doesNotContain("token");
		assertThat(body).doesNotContain("appointmentId");
	}

	@Test
	void customerFromAnotherOrganizationReadsEstimateAtAnyBranch() throws Exception {
		// ADR-040: cross-organization availability is the product working
		// as designed — a customer estimates at any ACTIVE branch. The
		// fixture org B has no queue row for its service, so the test
		// provisions one (queues exist per branch+service pair).
		queues.save(new Queue(orgB, branchB1, svcB1));
		HttpResponse<String> response = get(estimatePath(branchB1.getId(), svcB1.getId()), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
	}
}

