package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.staff.StaffAssignment;
import com.sourabh.smartqueue.staff.StaffAssignmentRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Service-session API proofs over real HTTP (see ADR-027, session reads).
 *
 * <p>Operational visibility tiers, current-session semantics, history
 * pagination, facet filtering, and the lifecycle integration
 * (join → call → start → session visible → complete → session ended) —
 * through the real filter chain. Non-transactional. Every flow builds a
 * dedicated queue + counter, so claims and counts never touch another
 * test's rows; direct capability construction uses only self-built
 * entities (no detached proxies outside transactions).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ServiceSessionApiTests {

	static final String PASSWORD = "Session-Api-17";
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

	String tag;
	Organization orgA;
	Branch branchA1;
	Branch branchA2;
	Organization orgB;
	Branch branchB1;

	String staffA1Token;
	String managerAToken;
	String adminAToken;
	String customerAToken;
	String managerBToken;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		tag = UUID.randomUUID().toString().substring(0, 8);
		orgA = organizations.save(new Organization("Session Org A " + tag));
		branchA1 = branches.save(new Branch(orgA, "Downtown " + tag,
				"Indore", "India", KOLKATA));
		branchA2 = branches.save(new Branch(orgA, "Uptown " + tag,
				"Bhopal", "India", KOLKATA));
		orgB = organizations.save(new Organization("Session Org B " + tag));
		branchB1 = branches.save(new Branch(orgB, "Riverside " + tag,
				"Pune", "India", KOLKATA));

		User staffA1 = newUser(orgA, "staff-a1-" + tag, RoleType.STAFF);
		assignments.save(new StaffAssignment(orgA, staffA1, branchA1));

		staffA1Token = tokenFor(staffA1);
		managerAToken = tokenFor(newUser(orgA, "mgr-a-" + tag, RoleType.MANAGER));
		adminAToken = tokenFor(newUser(orgA, "adm-a-" + tag, RoleType.ADMIN));
		customerAToken = tokenFor(newUser(orgA, "cust-a-" + tag, RoleType.CUSTOMER));
		managerBToken = tokenFor(newUser(orgB, "mgr-b-" + tag, RoleType.MANAGER));
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

	private String freshCustomerToken() {
		return tokenFor(newUser(orgA,
				"walkin-" + UUID.randomUUID().toString().substring(0, 8), RoleType.CUSTOMER));
	}

	private record Flow(Queue queue, ServiceCounter counter, String entryId) {
	}

	/**
	 * Join → call → start on a dedicated queue+counter, returning the live
	 * entry id. The serving session is then readable; completing it ends
	 * the session. All rows belong to this flow alone.
	 */
	private Flow startFlow() throws Exception {
		Service svc = services.save(new Service(branchA1,
				"Svc " + UUID.randomUUID().toString().substring(0, 8),
				Duration.ofMinutes(30)));
		Queue queue = queues.save(new Queue(orgA, branchA1, svc));
		ServiceCounter counter = counters.save(new ServiceCounter(branchA1,
				"Ctr " + UUID.randomUUID().toString().substring(0, 8)));
		capabilities.save(new CounterCapability(branchA1, svc, counter));

		String entryId = json(post(
				"/api/v1/branches/" + branchA1.getId() + "/queues/" + queue.getId()
						+ "/entries",
				"{}", freshCustomerToken()).body()).get("id").asString();
		assertThat(post("/api/v1/counters/" + counter.getId() + "/call-next", null,
				staffA1Token).statusCode()).isEqualTo(HttpStatus.OK.value());
		HttpResponse<String> started = post("/api/v1/queue-entries/" + entryId
				+ "/start-serving",
				"{\"counterId\":\"" + counter.getId() + "\"}", staffA1Token);
		assertThat(started.statusCode()).isEqualTo(HttpStatus.OK.value());
		return new Flow(queue, counter, entryId);
	}

	private String sessionIdForCounter(UUID counterId, String token) throws Exception {
		JsonNode page = json(get("/api/v1/counters/" + counterId + "/service-sessions",
				token).body());
		assertThat(page.get("totalElements").asLong()).isEqualTo(1);
		return page.get("content").get(0).get("id").asString();
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

	private JsonNode json(String body) {
		try {
			return objectMapper.readTree(body);
		} catch (tools.jackson.core.JacksonException e) {
			throw new IllegalStateException("unreadable JSON response: " + body, e);
		}
	}

	// -- Item reads -------------------------------------------------------------------------------

	@Test
	void sessionShapeAndVisibilityTiers() throws Exception {
		Flow flow = startFlow();
		String sessionId = sessionIdForCounter(flow.counter().getId(), managerAToken);

		HttpResponse<String> response =
				get("/api/v1/service-sessions/" + sessionId, managerAToken);
		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(body.get("counterId").asString())
				.isEqualTo(flow.counter().getId().toString());
		assertThat(body.get("queueEntryId").asString()).isEqualTo(flow.entryId());
		assertThat(body.get("queueId").asString())
				.isEqualTo(flow.queue().getId().toString());
		assertThat(body.get("branchId").asString())
				.isEqualTo(branchA1.getId().toString());
		assertThat(body.get("serviceName").asString()).isNotBlank();
		assertThat(body.get("customerId").asString()).isNotBlank();
		assertThat(body.get("active").asBoolean()).isTrue();
		assertThat(body.get("endedAt").isNull()).isTrue();
		assertThat(body.get("durationMinutes").isNull()).isTrue();
		assertThat(Instant.parse(body.get("startedAt").asString())).isBefore(
				Instant.now().plusSeconds(60));
		assertThat(response.body()).doesNotContain("passwordHash");

		assertThat(get("/api/v1/service-sessions/" + sessionId, adminAToken)
				.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(get("/api/v1/service-sessions/" + sessionId, staffA1Token)
				.statusCode()).isEqualTo(HttpStatus.OK.value());
		// Operational-only: even the session's own customer gets 404.
		assertThat(get("/api/v1/service-sessions/" + sessionId, customerAToken)
				.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(json(get("/api/v1/service-sessions/" + sessionId, customerAToken)
				.body()).get("code").asString()).isEqualTo("SERVICE_SESSION_NOT_FOUND");
	}

	@Test
	void sessionScopeIsolation() throws Exception {
		Flow flow = startFlow();
		String sessionId = sessionIdForCounter(flow.counter().getId(), managerAToken);

		assertThat(get("/api/v1/service-sessions/" + sessionId, managerBToken)
				.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		User other = newUser(orgA, "other-" + tag, RoleType.STAFF);
		assignments.save(new StaffAssignment(orgA, other, branchA2));
		assertThat(get("/api/v1/service-sessions/" + sessionId, tokenFor(other))
				.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/service-sessions/" + UUID.randomUUID(), managerAToken)
				.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/service-sessions/" + sessionId, null).statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	// -- Current session -------------------------------------------------------------------------------

	@Test
	void currentSessionTracksLifecycle() throws Exception {
		Flow flow = startFlow();
		String path = "/api/v1/counters/" + flow.counter().getId() + "/current-session";

		HttpResponse<String> running = get(path, staffA1Token);
		assertThat(running.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(running.body()).get("queueEntryId").asString())
				.isEqualTo(flow.entryId());
		assertThat(json(running.body()).get("active").asBoolean()).isTrue();

		post("/api/v1/queue-entries/" + flow.entryId() + "/complete", null,
				staffA1Token);
		// Idle counters have no current session: 404 doubles as "free".
		HttpResponse<String> idle = get(path, staffA1Token);
		assertThat(idle.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(json(idle.body()).get("code").asString())
				.isEqualTo("SERVICE_SESSION_NOT_FOUND");
	}

	@Test
	void currentSessionScopeAndRole() throws Exception {
		Flow flow = startFlow();
		String path = "/api/v1/counters/" + flow.counter().getId() + "/current-session";

		assertThat(get(path, managerAToken).statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(get(path, adminAToken).statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(get(path, customerAToken).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		User other = newUser(orgA, "idle-" + tag, RoleType.STAFF);
		assignments.save(new StaffAssignment(orgA, other, branchA2));
		assertThat(get(path, tokenFor(other)).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(get(path, managerBToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	// -- History and listing --------------------------------------------------------------------------------

	@Test
	void counterHistoryPaginatesNewestFirst() throws Exception {
		Flow flow = startFlow();
		post("/api/v1/queue-entries/" + flow.entryId() + "/complete", null,
				staffA1Token);
		String second = json(post(
				"/api/v1/branches/" + branchA1.getId() + "/queues/" + flow.queue().getId()
						+ "/entries",
				"{}", freshCustomerToken()).body()).get("id").asString();
		post("/api/v1/counters/" + flow.counter().getId() + "/call-next", null,
				staffA1Token);
		post("/api/v1/queue-entries/" + second + "/start-serving",
				"{\"counterId\":\"" + flow.counter().getId() + "\"}", staffA1Token);

		String path = "/api/v1/counters/" + flow.counter().getId() + "/service-sessions";
		JsonNode page = json(get(path, managerAToken).body());
		assertThat(page.get("totalElements").asLong()).isEqualTo(2);
		// Default: newest first.
		assertThat(page.get("content").get(0).get("queueEntryId").asString())
				.isEqualTo(second);
		JsonNode asc = json(get(path + "?sort=startedAt,asc&size=100",
				managerAToken).body());
		assertThat(asc.get("content").get(0).get("queueEntryId").asString())
				.isEqualTo(flow.entryId());
		JsonNode first = json(get(path + "?page=0&size=1", managerAToken).body());
		assertThat(first.get("content").size()).isEqualTo(1);
		assertThat(first.get("totalPages").asInt()).isEqualTo(2);
		assertThat(get(path, customerAToken).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	@Test
	void globalListWithFacets() throws Exception {
		Flow flow = startFlow();

		JsonNode all = json(get("/api/v1/service-sessions", managerAToken).body());
		assertThat(all.get("totalElements").asLong()).isGreaterThanOrEqualTo(1);

		JsonNode byCounter = json(get("/api/v1/service-sessions?counterId="
				+ flow.counter().getId(), managerAToken).body());
		assertThat(byCounter.get("totalElements").asLong()).isEqualTo(1);
		JsonNode byBranch = json(get("/api/v1/service-sessions?branchId="
				+ branchA1.getId() + "&size=100", managerAToken).body());
		assertThat(byBranch.get("totalElements").asLong()).isGreaterThanOrEqualTo(1);
		JsonNode running = json(get(
				"/api/v1/service-sessions?active=true&size=100", managerAToken).body());
		assertThat(running.get("totalElements").asLong()).isGreaterThanOrEqualTo(1);

		post("/api/v1/queue-entries/" + flow.entryId() + "/complete", null,
				staffA1Token);
		JsonNode ended = json(get(
				"/api/v1/service-sessions?active=false&counterId="
						+ flow.counter().getId(),
				managerAToken).body());
		assertThat(ended.get("totalElements").asLong()).isEqualTo(1);
		assertThat(ended.get("content").get(0).get("active").asBoolean()).isFalse();
		assertThat(ended.get("content").get(0).get("durationMinutes").asInt())
				.isGreaterThanOrEqualTo(0);

		assertThat(get("/api/v1/service-sessions", staffA1Token).statusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST.value());
		JsonNode staffPage = json(get("/api/v1/service-sessions?branchId="
				+ branchA1.getId(), staffA1Token).body());
		assertThat(staffPage.get("totalElements").asLong()).isGreaterThanOrEqualTo(1);
		assertThat(get("/api/v1/service-sessions", customerAToken).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(get("/api/v1/service-sessions?branchId=" + branchB1.getId(),
				managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void listWindowAndSortEdges() throws Exception {
		Flow flow = startFlow();
		String counterFacet =
				"counterId=" + flow.counter().getId() + "&size=100";

		Instant now = Instant.now();
		JsonNode inside = json(get("/api/v1/service-sessions?" + counterFacet
				+ "&from=" + now.minusSeconds(3600) + "&to=" + now.plusSeconds(3600),
				managerAToken).body());
		assertThat(inside.get("totalElements").asLong()).isEqualTo(1);
		JsonNode outside = json(get("/api/v1/service-sessions?" + counterFacet
				+ "&from=" + now.plusSeconds(3600) + "&to=" + now.plusSeconds(7200),
				managerAToken).body());
		assertThat(outside.get("totalElements").asLong()).isEqualTo(0);

		assertThat(get("/api/v1/service-sessions?from=" + now.plusSeconds(10)
				+ "&to=" + now.minusSeconds(10), managerAToken).statusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(get("/api/v1/service-sessions?sort=bogus", managerAToken)
				.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(get("/api/v1/service-sessions?size=101", managerAToken)
				.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
	}
}

