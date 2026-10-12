package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import com.sourabh.smartqueue.infrastructure.redis.RedisKeyConvention;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.QueueStatus;
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
 * Queue API proofs over real HTTP (see ADR-027).
 *
 * <p>Self-service joining, triage rules, privacy-first reads, operational
 * roster-gated commands, rank-ordered snapshots with shared waits,
 * lifecycle honesty, both cross-tenant directions, and the concurrent
 * claim invariant — through the real filter chain into the untouched
 * engine. Non-transactional.
 *
 * <p>Isolation discipline: every join uses a freshly created customer
 * (the duplicate-active-entry rule makes shared joiners order-dependent),
 * and every exact-count/order assertion uses a dedicated queue — no test
 * depends on another's rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class QueueApiTests {

	static final String PASSWORD = "Queue-Api-16";
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
	StringRedisTemplate redisTemplate;

	@Autowired
	ObjectMapper objectMapper;

	private final HttpClient http = HttpClient.newHttpClient();

	String tag;
	Organization orgA;
	Branch branchA1;
	Branch branchA2;
	Organization orgB;
	Branch branchB1;
	Service svcA1;
	Queue queueA1;
	Queue queueA2;
	Queue queueB1;
	ServiceCounter ctrA1;
	ServiceCounter ctrA1b;
	ServiceCounter ctrB1;

	String customerA1Token;
	String customerA2Token;
	String staffA1Token;
	String managerAToken;
	String adminAToken;
	String managerBToken;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		tag = UUID.randomUUID().toString().substring(0, 8);
		orgA = organizations.save(new Organization("Queue Org A " + tag));
		branchA1 = branches.save(new Branch(orgA, "Downtown " + tag,
				"Indore", "India", KOLKATA));
		branchA2 = branches.save(new Branch(orgA, "Uptown " + tag,
				"Bhopal", "India", KOLKATA));
		orgB = organizations.save(new Organization("Queue Org B " + tag));
		branchB1 = branches.save(new Branch(orgB, "Riverside " + tag,
				"Pune", "India", KOLKATA));

		svcA1 = services.save(new Service(branchA1, "Consult " + tag,
				Duration.ofMinutes(30)));
		Service svcA2 = services.save(new Service(branchA2, "Xray " + tag,
				Duration.ofMinutes(15)));
		Service svcB1 = services.save(new Service(branchB1, "Consult " + tag,
				Duration.ofMinutes(30)));
		queueA1 = queues.save(new Queue(orgA, branchA1, svcA1));
		queueA2 = queues.save(new Queue(orgA, branchA2, svcA2));
		queueB1 = queues.save(new Queue(orgB, branchB1, svcB1));
		ctrA1 = counters.save(new ServiceCounter(branchA1, "Counter 1 " + tag));
		ctrA1b = counters.save(new ServiceCounter(branchA1, "Counter 2 " + tag));
		ServiceCounter ctrA2 =
				counters.save(new ServiceCounter(branchA2, "Counter 9 " + tag));
		ctrB1 = counters.save(new ServiceCounter(branchB1, "Counter 1 " + tag));
		capabilities.save(new CounterCapability(branchA1, svcA1, ctrA1));
		capabilities.save(new CounterCapability(branchA1, svcA1, ctrA1b));
		capabilities.save(new CounterCapability(branchA2, svcA2, ctrA2));
		capabilities.save(new CounterCapability(branchB1, svcB1, ctrB1));

		User staffA1 = newUser(orgA, "staff-a1-" + tag, RoleType.STAFF);
		assignments.save(new StaffAssignment(orgA, staffA1, branchA1));

		customerA1Token = tokenFor(newUser(orgA, "cust-a1-" + tag, RoleType.CUSTOMER));
		customerA2Token = tokenFor(newUser(orgA, "cust-a2-" + tag, RoleType.CUSTOMER));
		staffA1Token = tokenFor(staffA1);
		managerAToken = tokenFor(newUser(orgA, "mgr-a-" + tag, RoleType.MANAGER));
		adminAToken = tokenFor(newUser(orgA, "adm-a-" + tag, RoleType.ADMIN));
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

	private String freshToken(RoleType role) {
		return tokenFor(newUser(orgA,
				"walkin-" + UUID.randomUUID().toString().substring(0, 8), role));
	}

	/** A customer that has joined nothing — every join below uses one. */
	private String freshCustomerToken() {
		return freshToken(RoleType.CUSTOMER);
	}

	/** A queue with no entries, for exact-count/order assertions. */
	private Queue freshQueue() {
		Service svc = services.save(new Service(branchA1,
				"Svc " + UUID.randomUUID().toString().substring(0, 8),
				Duration.ofMinutes(30)));
		return queues.save(new Queue(orgA, branchA1, svc));
	}

	/**
	 * A queue served by exactly one dedicated counter — claims inside such
	 * tests can only select their own entries, never another test's rows.
	 * The capability goes through the 3I HTTP API (which also keeps this
	 * class free of detached-proxy construction outside transactions).
	 */
	private record ServedQueue(Queue queue, ServiceCounter counter) {
	}

	private ServedQueue servedQueue() throws Exception {
		Service svc = services.save(new Service(branchA1,
				"Svc " + UUID.randomUUID().toString().substring(0, 8),
				Duration.ofMinutes(30)));
		Queue queue = queues.save(new Queue(orgA, branchA1, svc));
		ServiceCounter counter = counters.save(new ServiceCounter(branchA1,
				"Ctr " + UUID.randomUUID().toString().substring(0, 8)));
		HttpResponse<String> linked = post(
				"/api/v1/counters/" + counter.getId() + "/capabilities",
				"{\"serviceId\":\"" + svc.getId() + "\"}", managerAToken);
		assertThat(linked.statusCode()).isEqualTo(HttpStatus.CREATED.value());
		return new ServedQueue(queue, counter);
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

	private HttpResponse<String> join(UUID branchId, UUID queueId, String body,
			String token) throws Exception {
		return post("/api/v1/branches/" + branchId + "/queues/" + queueId + "/entries",
				body, token);
	}

	// -- Join -----------------------------------------------------------------------------

	@Test
	void customerJoinsWithServerTokenAndPosition() throws Exception {
		String firstToken = freshCustomerToken();
		String secondToken = freshCustomerToken();
		HttpResponse<String> first =
				join(branchA1.getId(), queueA1.getId(), "{}", firstToken);

		assertThat(first.statusCode()).isEqualTo(HttpStatus.CREATED.value());
		JsonNode body = json(first.body());
		assertThat(body.get("tokenNumber").asInt()).isEqualTo(1);
		assertThat(body.get("priority").asString()).isEqualTo("NORMAL");
		assertThat(body.get("status").asString()).isEqualTo("WAITING");
		assertThat(body.get("position").asInt()).isEqualTo(1);
		assertThat(body.get("waitMinutes").asInt()).isGreaterThanOrEqualTo(0);
		assertThat(body.get("queueId").asString()).isEqualTo(queueA1.getId().toString());
		assertThat(body.get("branchId").asString())
				.isEqualTo(branchA1.getId().toString());
		assertThat(body.get("customerId").asString()).isNotBlank();
		String location = first.headers().firstValue("Location").orElseThrow();
		assertThat(location).endsWith("/api/v1/queue-entries/" + body.get("id").asString());
		assertThat(first.body()).doesNotContain("passwordHash");

		HttpResponse<String> second =
				join(branchA1.getId(), queueA1.getId(), "{}", secondToken);
		assertThat(second.statusCode()).isEqualTo(HttpStatus.CREATED.value());
		assertThat(json(second.body()).get("tokenNumber").asInt()).isEqualTo(2);
		assertThat(json(second.body()).get("position").asInt()).isEqualTo(2);
	}

	@Test
	void everyRoleJoinsForSelfOnly() throws Exception {
		String staffToken = tokenFor(newUser(orgA,
				"joinstaff-" + tag, RoleType.STAFF));
		String managerToken = tokenFor(newUser(orgA,
				"joinmgr-" + tag, RoleType.MANAGER));

		// No customerId field exists to substitute: each booking names its bearer.
		User staff = users.findByEmail("joinstaff-" + tag + "@example.com").orElseThrow();
		assertThat(json(join(branchA1.getId(), queueA1.getId(), "{}",
				staffToken).body()).get("customerId").asString())
				.isEqualTo(staff.getId().toString());
		assertThat(join(branchA1.getId(), queueA1.getId(), "{}",
				managerToken).statusCode()).isEqualTo(HttpStatus.CREATED.value());
		assertThat(join(branchA1.getId(), queueA1.getId(), "{}", null).statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	@Test
	void priorityRequiresOperationalAuthority() throws Exception {
		// Managers triage freely.
		HttpResponse<String> triaged = join(branchA1.getId(), queueA1.getId(),
				"{\"priority\":\"PRIORITY\"}", managerAToken);
		assertThat(triaged.statusCode()).isEqualTo(HttpStatus.CREATED.value());
		assertThat(json(triaged.body()).get("priority").asString()).isEqualTo("PRIORITY");

		// Customers never self-declare urgency…
		assertThat(join(branchA1.getId(), queueA1.getId(), "{\"priority\":\"EMERGENCY\"}",
				customerA2Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		// …nor do staff outside their branch.
		assertThat(join(branchA2.getId(), queueA2.getId(), "{\"priority\":\"PRIORITY\"}",
				staffA1Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		// Unknown names are malformed, not forbidden.
		assertThat(join(branchA1.getId(), queueA1.getId(), "{\"priority\":\"VIP\"}",
				managerAToken).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
	}

	@Test
	void duplicateActiveJoinConflicts() throws Exception {
		String token = freshCustomerToken();
		assertThat(join(branchA1.getId(), queueA1.getId(), "{}", token)
				.statusCode()).isEqualTo(HttpStatus.CREATED.value());
		HttpResponse<String> duplicate =
				join(branchA1.getId(), queueA1.getId(), "{}", token);
		assertThat(duplicate.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(json(duplicate.body()).get("code").asString())
				.isEqualTo("QUEUE_CONFLICT");
	}

	@Test
	void joinScopeMismatchAndNotFound() throws Exception {
		String token = freshCustomerToken();
		// Same org, wrong branch in path: 422.
		HttpResponse<String> mismatch = join(branchA2.getId(), queueA1.getId(), "{}",
				token);
		assertThat(mismatch.statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
		assertThat(json(mismatch.body()).get("code").asString())
				.isEqualTo("QUEUE_BRANCH_MISMATCH");
		// ADR-040: a platform-level customer is not bound to one
		// organization — a queue at another branch of any ACTIVE
		// organization is the same 422 mismatch as the same-org case.
		// Unknown ids stay scope-safe 404s, never the 422.
		assertThat(join(branchA1.getId(), queueB1.getId(), "{}",
				token).statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
		assertThat(join(branchB1.getId(), queueA1.getId(), "{}", token)
				.statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
		assertThat(join(branchA1.getId(), UUID.randomUUID(), "{}", token)
				.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(json(join(branchA1.getId(), queueB1.getId(), "{}",
				token).body()).get("code").asString())
				.isEqualTo("QUEUE_BRANCH_MISMATCH");
	}

	@Test
	void joinInactiveQueueConflicts() throws Exception {
		Service svc = services.save(new Service(branchA1, "Paused " + tag,
				Duration.ofMinutes(10)));
		Queue idle = queues.save(new Queue(orgA, branchA1, svc));
		idle.setStatus(QueueStatus.INACTIVE);
		queues.save(idle);

		HttpResponse<String> response =
				join(branchA1.getId(), idle.getId(), "{}", freshCustomerToken());
		assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(json(response.body()).get("code").asString())
				.isEqualTo("QUEUE_NOT_ACTIVE");
	}

	// -- Retrieval --------------------------------------------------------------------------

	@Test
	void queueListIsBranchDisplay() throws Exception {
		HttpResponse<String> response =
				get("/api/v1/branches/" + branchA1.getId() + "/queues", customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(body.size()).isGreaterThanOrEqualTo(1);
		assertThat(body.get(0).get("branchId").asString())
				.isEqualTo(branchA1.getId().toString());
		assertThat(body.get(0).get("serviceName").asString()).isNotBlank();

		assertThat(get("/api/v1/branches/" + branchA1.getId() + "/queues",
				staffA1Token).statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(get("/api/v1/branches/" + branchA2.getId() + "/queues",
				staffA1Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(get("/api/v1/branches/" + branchB1.getId() + "/queues",
				managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/branches/" + branchA1.getId() + "/queues", null)
				.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	@Test
	void queueDetailRanksAndCounts() throws Exception {
		Queue queue = freshQueue();
		join(branchA1.getId(), queue.getId(), "{}", freshCustomerToken());
		join(branchA1.getId(), queue.getId(), "{}", freshCustomerToken());

		HttpResponse<String> response =
				get("/api/v1/queues/" + queue.getId(), managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(body.get("waitingCount").asInt()).isEqualTo(2);
		assertThat(body.get("queue").get("id").asString())
				.isEqualTo(queue.getId().toString());
		assertThat(body.get("generatedAt").asString()).isNotBlank();
		JsonNode entries = body.get("entries");
		assertThat(entries.size()).isEqualTo(2);
		assertThat(entries.get(0).get("position").asInt()).isEqualTo(1);
		assertThat(entries.get(1).get("position").asInt()).isEqualTo(2);
		assertThat(entries.get(0).get("waitMinutes").asInt()).isGreaterThanOrEqualTo(0);
		assertThat(entries.get(0).get("id").asString()).isNotBlank();

		assertThat(get("/api/v1/queues/" + queue.getId(), customerA1Token).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(get("/api/v1/queues/" + queue.getId(), staffA1Token).statusCode())
				.isEqualTo(HttpStatus.OK.value());
		assertThat(get("/api/v1/queues/" + queueB1.getId(), managerAToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void entryReadIsPrivacyFirst() throws Exception {
		String token = freshCustomerToken();
		String id = json(join(branchA1.getId(), queueA1.getId(), "{}",
				token).body()).get("id").asString();

		assertThat(get("/api/v1/queue-entries/" + id, token).statusCode())
				.isEqualTo(HttpStatus.OK.value());
		assertThat(get("/api/v1/queue-entries/" + id, managerAToken).statusCode())
				.isEqualTo(HttpStatus.OK.value());
		assertThat(get("/api/v1/queue-entries/" + id, staffA1Token).statusCode())
				.isEqualTo(HttpStatus.OK.value());
		// Same-org customers and unassigned staff get 404 — never a peek.
		assertThat(get("/api/v1/queue-entries/" + id, customerA2Token).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		User other = newUser(orgA, "other-" + tag, RoleType.STAFF);
		assignments.save(new StaffAssignment(orgA, other, branchA2));
		assertThat(get("/api/v1/queue-entries/" + id, tokenFor(other)).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/queue-entries/" + id, managerBToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(json(get("/api/v1/queue-entries/" + id, managerBToken).body())
				.get("code").asString()).isEqualTo("QUEUE_ENTRY_NOT_FOUND");
	}

	@Test
	void entriesListIsOperationalWithFilters() throws Exception {
		Queue queue = freshQueue();
		String firstToken = freshCustomerToken();
		String secondToken = freshCustomerToken();
		join(branchA1.getId(), queue.getId(), "{}", firstToken);
		join(branchA1.getId(), queue.getId(), "{}", secondToken);
		String path = "/api/v1/queues/" + queue.getId() + "/entries";

		HttpResponse<String> response = get(path, managerAToken);
		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode page = json(response.body());
		assertThat(page.get("totalElements").asLong()).isEqualTo(2);
		// Bulk pages carry no live positions/waits (documented cost bound).
		assertThat(page.get("content").get(0).get("position").isNull()).isTrue();

		JsonNode waiting = json(
				get(path + "?status=WAITING&size=100", managerAToken).body());
		assertThat(waiting.get("totalElements").asLong()).isEqualTo(2);
		JsonNode called = json(
				get(path + "?status=CALLED&size=100", managerAToken).body());
		assertThat(called.get("totalElements").asLong()).isEqualTo(0);

		assertThat(get(path, staffA1Token).statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(get(path, customerA1Token).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(get(path + "?status=BOGUS", managerAToken).statusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(get(path + "?size=101", managerAToken).statusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(get("/api/v1/queues/" + queueB1.getId() + "/entries", managerAToken)
				.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	// -- Call next ------------------------------------------------------------------------------

	@Test
	void callNextServesRankOrder() throws Exception {
		ServedQueue served = servedQueue();
		Queue queue = served.queue();
		ServiceCounter counter = served.counter();
		join(branchA1.getId(), queue.getId(), "{}", freshCustomerToken());
		join(branchA1.getId(), queue.getId(), "{\"priority\":\"PRIORITY\"}",
				freshToken(RoleType.MANAGER));

		HttpResponse<String> called = post(
				"/api/v1/counters/" + counter.getId() + "/call-next", null, staffA1Token);

		assertThat(called.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(called.body());
		assertThat(body.get("priority").asString()).isEqualTo("PRIORITY");
		assertThat(body.get("status").asString()).isEqualTo("CALLED");
		assertThat(body.get("position").asInt()).isEqualTo(0);
		assertThat(body.get("waitMinutes").asInt()).isEqualTo(0);
		assertThat(body.get("queueId").asString()).isEqualTo(queue.getId().toString());
	}

	@Test
	void callNextMatrixAndEmptyQueue() throws Exception {
		assertThat(post("/api/v1/counters/" + ctrA1.getId() + "/call-next", null,
				customerA1Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		User other = newUser(orgA, "caller-" + tag, RoleType.STAFF);
		assignments.save(new StaffAssignment(orgA, other, branchA2));
		assertThat(post("/api/v1/counters/" + ctrA1.getId() + "/call-next", null,
				tokenFor(other)).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post("/api/v1/counters/" + ctrB1.getId() + "/call-next", null,
				managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(post("/api/v1/counters/" + UUID.randomUUID() + "/call-next", null,
				managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());

		// Empty claim set: documented 409, never a server error.
		Service lone = services.save(new Service(branchA1, "Lone " + tag,
				Duration.ofMinutes(10)));
		Queue loneQueue = queues.save(new Queue(orgA, branchA1, lone));
		ServiceCounter loneCounter =
				counters.save(new ServiceCounter(branchA1, "LoneCtr " + tag));
		capabilities.save(new CounterCapability(branchA1, lone, loneCounter));
		HttpResponse<String> empty = post(
				"/api/v1/counters/" + loneCounter.getId() + "/call-next", null,
				managerAToken);
		assertThat(empty.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(json(empty.body()).get("code").asString())
				.isEqualTo("NO_WAITING_ENTRY");
		assertThat(loneQueue.getId()).isNotNull();
	}

	@Test
	void concurrentCallNextNeverSharesAnEntry() throws Exception {
		ServedQueue served = servedQueue();
		Queue queue = served.queue();
		ServiceCounter counter = served.counter();
		join(branchA1.getId(), queue.getId(), "{}", freshCustomerToken());
		join(branchA1.getId(), queue.getId(), "{}", freshCustomerToken());

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			String url = "http://localhost:" + port + "/api/v1/counters/"
					+ counter.getId() + "/call-next";
			Callable<String> call = () -> {
				HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
						.header("Content-Type", "application/json")
						.header("Authorization", "Bearer " + managerAToken)
						.POST(HttpRequest.BodyPublishers.ofString("")).build();
				HttpResponse<String> response =
						HttpClient.newHttpClient().send(request,
								HttpResponse.BodyHandlers.ofString());
				assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
				return json(response.body()).get("id").asString();
			};
			List<Future<String>> results = pool.invokeAll(List.of(call, call));
			assertThat(results.get(0).get()).isNotEqualTo(results.get(1).get());
		} finally {
			pool.shutdownNow();
		}
	}

	// -- Lifecycle -----------------------------------------------------------------------------------

	@Test
	void fullOperationalFlow() throws Exception {
		ServedQueue served = servedQueue();
		Queue queue = served.queue();
		ServiceCounter counter = served.counter();
		String callPath = "/api/v1/counters/" + counter.getId() + "/call-next";
		String id = json(join(branchA1.getId(), queue.getId(), "{}",
				freshCustomerToken()).body()).get("id").asString();
		post(callPath, null, staffA1Token);

		HttpResponse<String> serving = post("/api/v1/queue-entries/" + id
				+ "/start-serving",
				"{\"counterId\":\"" + counter.getId() + "\"}", staffA1Token);
		assertThat(serving.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(serving.body()).get("status").asString()).isEqualTo("SERVING");

		HttpResponse<String> done =
				post("/api/v1/queue-entries/" + id + "/complete", null, staffA1Token);
		assertThat(done.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(done.body()).get("status").asString()).isEqualTo("COMPLETED");
		assertThat(json(done.body()).get("position").isNull()).isTrue();
	}

	@Test
	void lifecycleGuards() throws Exception {
		ServedQueue served = servedQueue();
		Queue queue = served.queue();
		ServiceCounter counter = served.counter();
		String id = json(join(branchA1.getId(), queue.getId(), "{}",
				freshCustomerToken()).body()).get("id").asString();
		String waiting = json(join(branchA1.getId(), queue.getId(), "{}",
				freshCustomerToken()).body()).get("id").asString();

		// WAITING cannot start serving or complete.
		assertThat(post("/api/v1/queue-entries/" + waiting + "/start-serving",
				"{\"counterId\":\"" + counter.getId() + "\"}", staffA1Token).statusCode())
				.isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(post("/api/v1/queue-entries/" + waiting + "/complete", null,
				staffA1Token).statusCode()).isEqualTo(HttpStatus.CONFLICT.value());

		// Incapable and cross-branch counters are 400s; unknown and
		// foreign-organization counters are scope-safe 404s.
		post("/api/v1/counters/" + counter.getId() + "/call-next", null, staffA1Token);
		ServiceCounter incapable =
				counters.save(new ServiceCounter(branchA1, "Idle " + tag));
		assertThat(post("/api/v1/queue-entries/" + id + "/start-serving",
				"{\"counterId\":\"" + incapable.getId() + "\"}", staffA1Token)
				.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		ServiceCounter otherBranch =
				counters.save(new ServiceCounter(branchA2, "Remote " + tag));
		assertThat(post("/api/v1/queue-entries/" + id + "/start-serving",
				"{\"counterId\":\"" + otherBranch.getId() + "\"}", staffA1Token)
				.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(post("/api/v1/queue-entries/" + id + "/start-serving",
				"{\"counterId\":\"" + ctrB1.getId() + "\"}", staffA1Token)
				.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(post("/api/v1/queue-entries/" + id + "/start-serving",
				"{\"counterId\":\"" + UUID.randomUUID() + "\"}", staffA1Token)
				.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());

		// Customers cannot start service, even their own.
		assertThat(post("/api/v1/queue-entries/" + id + "/start-serving",
				"{\"counterId\":\"" + counter.getId() + "\"}", customerA1Token)
				.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	@Test
	void cancelSemantics() throws Exception {
		String token = freshCustomerToken();
		String id = json(join(branchA1.getId(), queueA1.getId(), "{}",
				token).body()).get("id").asString();

		HttpResponse<String> cancelled =
				post("/api/v1/queue-entries/" + id + "/cancel", null, token);
		assertThat(cancelled.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(cancelled.body()).get("status").asString()).isEqualTo("CANCELLED");

		// Terminal rows refuse — 409, the domain rule wins over idempotency.
		HttpResponse<String> again =
				post("/api/v1/queue-entries/" + id + "/cancel", null, token);
		assertThat(again.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(json(again.body()).get("code").asString())
				.isEqualTo("INVALID_QUEUE_STATE");

		// The freed active slot re-joins immediately.
		assertThat(join(branchA1.getId(), queueA1.getId(), "{}", token)
				.statusCode()).isEqualTo(HttpStatus.CREATED.value());
	}

	@Test
	void cancelAuthorization() throws Exception {
		String id = json(join(branchA1.getId(), queueA1.getId(), "{}",
				freshCustomerToken()).body()).get("id").asString();

		assertThat(post("/api/v1/queue-entries/" + id + "/cancel", null,
				customerA2Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post("/api/v1/queue-entries/" + id + "/cancel", null,
				staffA1Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post("/api/v1/queue-entries/" + id + "/cancel", null,
				managerAToken).statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(post("/api/v1/queue-entries/" + id + "/cancel", null,
				managerBToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void noShowSemantics() throws Exception {
		String id = json(join(branchA1.getId(), queueA1.getId(), "{}",
				freshCustomerToken()).body()).get("id").asString();

		assertThat(json(post("/api/v1/queue-entries/" + id + "/no-show", null,
				staffA1Token).body()).get("status").asString()).isEqualTo("NO_SHOW");
		assertThat(post("/api/v1/queue-entries/" + id + "/no-show", null,
				staffA1Token).statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(post("/api/v1/queue-entries/" + id + "/no-show", null,
				customerA1Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	@Test
	void zeroCapableCountersMeansUnknownWait() throws Exception {
		Service lone = services.save(new Service(branchA1, "Unserved " + tag,
				Duration.ofMinutes(10)));
		Queue queue = queues.save(new Queue(orgA, branchA1, lone));

		JsonNode body = json(join(branchA1.getId(), queue.getId(), "{}",
				freshCustomerToken()).body());
		// Rank still works (position 1); the wait is honestly unknown.
		assertThat(body.get("position").asInt()).isEqualTo(1);
		assertThat(body.get("waitMinutes").isNull()).isTrue();
	}

	@Test
	void restJoinFeedsRealtimeProjection() throws Exception {
		JsonNode body = json(join(branchA1.getId(), queueA1.getId(), "{}",
				freshCustomerToken()).body());

		// The AFTER_COMMIT bridge runs before the 201 returns: the derived
		// snapshot already carries the token. REST never reads it back —
		// this only proves the event flow stayed intact.
		String key = RedisKeyConvention.queueState(
				orgA.getId(), branchA1.getId(), queueA1.getId());
		String raw = null;
		for (int i = 0; i < 20 && raw == null; i++) {
			raw = redisTemplate.opsForValue().get(key);
			if (raw == null) {
				Thread.sleep(250);
			}
		}
		assertThat(raw).isNotNull();
		assertThat(raw).contains("\"tokenNumber\":" + body.get("tokenNumber").asInt());
	}
}

