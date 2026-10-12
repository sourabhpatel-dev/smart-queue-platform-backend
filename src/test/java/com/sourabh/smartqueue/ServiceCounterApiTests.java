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
import com.sourabh.smartqueue.counter.CounterCapabilityRepository;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
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
 * Service/counter/capability API proofs over real HTTP (see ADR-024).
 *
 * <p>Role matrix, both cross-tenant directions, same-org cross-branch
 * capability rejection, pagination/sort/filter edges, constraint-arbitrated
 * conflicts, and lifecycle idempotency — all through the real filter chain.
 * Non-transactional; ordering-sensitive tests use dedicated branches so no
 * test depends on another's rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ServiceCounterApiTests {

	static final String PASSWORD = "Svc-Ctr-13";
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
	Service svcA1;
	Service svcA2;
	Service svcB1;
	ServiceCounter ctrA1;
	ServiceCounter ctrB1;

	String managerAToken;
	String adminAToken;
	String staffA1Token;
	String customerAToken;
	String managerBToken;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		tag = UUID.randomUUID().toString().substring(0, 8);
		orgA = organizations.save(new Organization("Svc Org A " + tag));
		branchA1 = branches.save(new Branch(orgA, "Downtown " + tag,
				"Indore", "India", KOLKATA));
		branchA2 = branches.save(new Branch(orgA, "Uptown " + tag,
				"Bhopal", "India", KOLKATA));
		orgB = organizations.save(new Organization("Svc Org B " + tag));
		branchB1 = branches.save(new Branch(orgB, "Riverside " + tag,
				"Pune", "India", KOLKATA));

		svcA1 = service(branchA1, "Consult " + tag, 30);
		svcA2 = service(branchA2, "Xray " + tag, 15);
		svcB1 = service(branchB1, "Consult " + tag, 30);
		ctrA1 = counters.save(new ServiceCounter(branchA1, "Counter 1 " + tag));
		ctrB1 = counters.save(new ServiceCounter(branchB1, "Counter 1 " + tag));

		managerAToken = tokenFor(newUser(orgA, "mgr-a-" + tag, RoleType.MANAGER));
		adminAToken = tokenFor(newUser(orgA, "adm-a-" + tag, RoleType.ADMIN));
		User staffA1 = newUser(orgA, "staff-a1-" + tag, RoleType.STAFF);
		assignments.save(new StaffAssignment(orgA, staffA1, branchA1));
		staffA1Token = tokenFor(staffA1);
		customerAToken = tokenFor(newUser(orgA, "cust-a-" + tag, RoleType.CUSTOMER));
		managerBToken = tokenFor(newUser(orgB, "mgr-b-" + tag, RoleType.MANAGER));
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private Service service(Branch branch, String name, int minutes) {
		Service service = new Service(branch, name, Duration.ofMinutes(minutes));
		return services.save(service);
	}

	private Branch freshBranch(Organization org, String name) {
		return branches.save(new Branch(org, name + " " + tag, "Indore", "India", KOLKATA));
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

	private HttpResponse<String> put(String path, String body, String token) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.PUT(HttpRequest.BodyPublishers.ofString(body));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> delete(String path, String token) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path))
				.DELETE();
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

	private String svcPayload(String name, int minutes) {
		return "{\"name\":\"" + name + "\",\"description\":\"General\","
				+ "\"durationMinutes\":" + minutes + "}";
	}

	private List<String> contentNames(JsonNode page) {
		List<String> names = new ArrayList<>();
		page.get("content").forEach(node -> names.add(node.get("name").asString()));
		return names;
	}

	private List<String> capabilityNames(JsonNode array) {
		List<String> names = new ArrayList<>();
		array.forEach(node -> names.add(node.get("serviceName").asString()));
		return names;
	}

	// -- Service list ------------------------------------------------------------

	@Test
	void listServicesIsBranchScoped() throws Exception {
		HttpResponse<String> response = get(
				"/api/v1/branches/" + branchA1.getId() + "/services", managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode page = json(response.body());
		assertThat(page.get("page").asInt()).isEqualTo(0);
		assertThat(page.get("size").asInt()).isEqualTo(20);
		List<String> names = contentNames(page);
		assertThat(names).contains("Consult " + tag);
		assertThat(names).doesNotContain("Xray " + tag);
	}

	@Test
	void listServicesForeignOrUnknownBranchIs404() throws Exception {
		assertThat(get("/api/v1/branches/" + branchB1.getId() + "/services",
				managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/branches/" + branchA1.getId() + "/services",
				managerBToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/branches/" + UUID.randomUUID() + "/services",
				managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/branches/" + branchA1.getId() + "/services", null)
				.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	@Test
	void serviceListReadMatrix() throws Exception {
		String path = "/api/v1/branches/" + branchA1.getId() + "/services";
		assertThat(get(path, staffA1Token).statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(get(path, customerAToken).statusCode()).isEqualTo(HttpStatus.OK.value());
		// Assigned to A1 only: A2's list is forbidden.
		assertThat(get("/api/v1/branches/" + branchA2.getId() + "/services",
				staffA1Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	@Test
	void serviceListPaginationSortingAndFilter() throws Exception {
		Branch branch = freshBranch(orgA, "PagedSvc");
		service(branch, "Echo", 45);
		service(branch, "Blood", 10);
		service(branch, "Consult", 30);
		service(branch, "Dental", 60);
		service(branch, "Physio", 20);

		List<String> byDuration = contentNames(json(get(
				"/api/v1/branches/" + branch.getId()
						+ "/services?sort=durationMinutes,asc&size=100",
				managerAToken).body()));
		assertThat(byDuration).containsExactly("Blood", "Physio", "Consult", "Echo", "Dental");

		JsonNode first = json(get(
				"/api/v1/branches/" + branch.getId() + "/services?page=0&size=2",
				managerAToken).body());
		assertThat(first.get("content").size()).isEqualTo(2);
		assertThat(first.get("totalElements").asLong()).isEqualTo(5);
		assertThat(first.get("totalPages").asInt()).isEqualTo(3);
		JsonNode beyond = json(get(
				"/api/v1/branches/" + branch.getId() + "/services?page=9&size=2",
				managerAToken).body());
		assertThat(beyond.get("content").size()).isEqualTo(0);

		HttpResponse<String> badSort = get(
				"/api/v1/branches/" + branch.getId() + "/services?sort=nope,asc",
				managerAToken);
		assertThat(badSort.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(json(badSort.body()).get("code").asString()).isEqualTo("INVALID_SORT");
		assertThat(get("/api/v1/branches/" + branch.getId() + "/services?size=101",
				managerAToken).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());

		Service retiring = service(branch, "Retiring", 25);
		post("/api/v1/services/" + retiring.getId() + "/deactivate", null, managerAToken);
		List<String> inactive = contentNames(json(get(
				"/api/v1/branches/" + branch.getId() + "/services?status=INACTIVE&size=100",
				managerAToken).body()));
		assertThat(inactive).containsExactly("Retiring");
		assertThat(get("/api/v1/branches/" + branch.getId() + "/services?status=BOGUS",
				managerAToken).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
	}

	@Test
	void listServicesEmptyBranchIsEmptyPage() throws Exception {
		Branch empty = freshBranch(orgA, "EmptySvc");
		JsonNode page = json(get(
				"/api/v1/branches/" + empty.getId() + "/services", managerAToken).body());
		assertThat(page.get("content").size()).isEqualTo(0);
		assertThat(page.get("totalElements").asLong()).isEqualTo(0);
	}

	// -- Service item --------------------------------------------------------------

	@Test
	void getServiceShapeAndScope() throws Exception {
		HttpResponse<String> response =
				get("/api/v1/services/" + svcA1.getId(), managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(UUID.fromString(body.get("id").asString())).isEqualTo(svcA1.getId());
		assertThat(UUID.fromString(body.get("branchId").asString()))
				.isEqualTo(branchA1.getId());
		assertThat(UUID.fromString(body.get("organizationId").asString()))
				.isEqualTo(orgA.getId());
		assertThat(body.get("name").asString()).isEqualTo("Consult " + tag);
		assertThat(body.get("durationMinutes").asInt()).isEqualTo(30);
		assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(response.body()).doesNotContain("passwordHash");

		assertThat(get("/api/v1/services/" + svcB1.getId(), managerAToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/services/" + svcA1.getId(), managerBToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/services/" + UUID.randomUUID(), managerAToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void getServiceReadMatrix() throws Exception {
		String path = "/api/v1/services/" + svcA1.getId();
		assertThat(get(path, adminAToken).statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(get(path, staffA1Token).statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(get(path, customerAToken).statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(get("/api/v1/services/" + svcA2.getId(), staffA1Token).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	// -- Service writes ---------------------------------------------------------------

	@Test
	void managerAndAdminCreateService() throws Exception {
		HttpResponse<String> response = post(
				"/api/v1/branches/" + branchA1.getId() + "/services",
				svcPayload("Blood " + tag, 10), managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
		JsonNode body = json(response.body());
		assertThat(body.get("branchId").asString()).isEqualTo(branchA1.getId().toString());
		assertThat(body.get("organizationId").asString())
				.isEqualTo(orgA.getId().toString());
		assertThat(body.get("durationMinutes").asInt()).isEqualTo(10);
		assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
		String location = response.headers().firstValue("Location").orElseThrow();
		assertThat(location).endsWith("/api/v1/services/" + body.get("id").asString());

		assertThat(post("/api/v1/branches/" + branchA1.getId() + "/services",
				svcPayload("Admin " + tag, 20), adminAToken).statusCode())
				.isEqualTo(HttpStatus.CREATED.value());
	}

	@Test
	void serviceWriteMatrix() throws Exception {
		String list = "/api/v1/branches/" + branchA1.getId() + "/services";
		assertThat(post(list, svcPayload("Staff " + tag, 10), staffA1Token).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post(list, svcPayload("Cust " + tag, 10), customerAToken).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post(list, svcPayload("Anon " + tag, 10), null).statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());

		assertThat(put("/api/v1/services/" + svcA1.getId(),
				svcPayload("Staff " + tag, 10), staffA1Token).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post("/api/v1/services/" + svcA1.getId() + "/deactivate", null,
				customerAToken).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	@Test
	void createServiceValidation() throws Exception {
		String path = "/api/v1/branches/" + branchA1.getId() + "/services";
		assertThat(post(path, "{\"name\":\"\",\"durationMinutes\":10}", managerAToken)
				.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(post(path, "{\"name\":\"NoDur " + tag + "\"}", managerAToken)
				.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(post(path, svcPayload("Zero " + tag, 0), managerAToken)
				.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(post(path,
				"{\"name\":\"Long " + tag + "\",\"description\":\"" + "x".repeat(1001)
						+ "\",\"durationMinutes\":10}",
				managerAToken).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
	}

	@Test
	void duplicateServiceNameConflictsPerBranchOnly() throws Exception {
		HttpResponse<String> duplicate = post(
				"/api/v1/branches/" + branchA1.getId() + "/services",
				svcPayload("Consult " + tag, 30), managerAToken);
		assertThat(duplicate.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(json(duplicate.body()).get("code").asString())
				.isEqualTo("SERVICE_NAME_CONFLICT");
		assertThat(duplicate.body()).doesNotContain("uq_service_branch_name");

		// Same name at another branch of the same org stays legal.
		assertThat(post("/api/v1/branches/" + branchA2.getId() + "/services",
				svcPayload("Consult " + tag, 30), managerAToken).statusCode())
				.isEqualTo(HttpStatus.CREATED.value());
	}

	@Test
	void updateServiceSemantics() throws Exception {
		Service target = service(branchA1, "Rename " + tag, 30);
		HttpResponse<String> response = put("/api/v1/services/" + target.getId(),
				"{\"name\":\"Renamed " + tag
						+ "\",\"description\":\"Updated\",\"durationMinutes\":45}",
				managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(body.get("name").asString()).isEqualTo("Renamed " + tag);
		assertThat(body.get("durationMinutes").asInt()).isEqualTo(45);
		assertThat(body.get("branchId").asString()).isEqualTo(branchA1.getId().toString());

		assertThat(put("/api/v1/services/" + svcB1.getId(),
				svcPayload("Remote " + tag, 10), managerAToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		HttpResponse<String> clash = put("/api/v1/services/" + target.getId(),
				svcPayload("Consult " + tag, 30), managerAToken);
		assertThat(clash.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(put("/api/v1/services/" + target.getId(),
				"{\"name\":\"\",\"durationMinutes\":10}", managerAToken).statusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST.value());
	}

	@Test
	void serviceLifecycleIsIdempotent() throws Exception {
		Service target = service(branchA1, "CyclingSvc " + tag, 30);

		assertThat(json(post("/api/v1/services/" + target.getId() + "/deactivate", null,
				managerAToken).body()).get("status").asString()).isEqualTo("INACTIVE");
		assertThat(post("/api/v1/services/" + target.getId() + "/deactivate", null,
				managerAToken).statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(post("/api/v1/services/" + target.getId() + "/activate", null,
				adminAToken).body()).get("status").asString()).isEqualTo("ACTIVE");
		assertThat(post("/api/v1/services/" + svcB1.getId() + "/deactivate", null,
				managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	// -- Counters ------------------------------------------------------------------

	@Test
	void counterListGetAndScope() throws Exception {
		JsonNode page = json(get(
				"/api/v1/branches/" + branchA1.getId() + "/counters", managerAToken).body());
		assertThat(contentNames(page)).contains("Counter 1 " + tag);
		assertThat(page.get("totalElements").asLong()).isGreaterThanOrEqualTo(1);

		JsonNode body = json(
				get("/api/v1/counters/" + ctrA1.getId(), customerAToken).body());
		assertThat(body.get("name").asString()).isEqualTo("Counter 1 " + tag);
		assertThat(body.get("branchId").asString()).isEqualTo(branchA1.getId().toString());
		assertThat(body.get("status").asString()).isEqualTo("ACTIVE");

		assertThat(get("/api/v1/counters/" + ctrB1.getId(), managerAToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/counters/" + ctrA1.getId(), managerBToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/branches/" + branchB1.getId() + "/counters",
				managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void counterReadMatrix() throws Exception {
		String path = "/api/v1/counters/" + ctrA1.getId();
		assertThat(get(path, adminAToken).statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(get(path, staffA1Token).statusCode()).isEqualTo(HttpStatus.OK.value());
		ServiceCounter other = counters.save(
				new ServiceCounter(branchA2, "Counter 9 " + tag));
		assertThat(get("/api/v1/counters/" + other.getId(), staffA1Token).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	@Test
	void counterListPaginationSortFilter() throws Exception {
		Branch branch = freshBranch(orgA, "PagedCtr");
		counters.save(new ServiceCounter(branch, "Zulu"));
		counters.save(new ServiceCounter(branch, "Alfa"));

		List<String> names = contentNames(json(get(
				"/api/v1/branches/" + branch.getId() + "/counters?sort=name,asc&size=100",
				managerAToken).body()));
		assertThat(names).containsExactly("Alfa", "Zulu");
		assertThat(get("/api/v1/branches/" + branch.getId() + "/counters?sort=bogus",
				managerAToken).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());

		ServiceCounter idle = counters.save(new ServiceCounter(branch, "Idle"));
		post("/api/v1/counters/" + idle.getId() + "/deactivate", null, managerAToken);
		List<String> inactive = contentNames(json(get(
				"/api/v1/branches/" + branch.getId() + "/counters?status=INACTIVE&size=100",
				managerAToken).body()));
		assertThat(inactive).containsExactly("Idle");
		assertThat(get("/api/v1/branches/" + branch.getId() + "/counters?status=BOGUS",
				managerAToken).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
	}

	@Test
	void createCounterSemantics() throws Exception {
		HttpResponse<String> response = post(
				"/api/v1/branches/" + branchA1.getId() + "/counters",
				"{\"name\":\"Counter 2 " + tag + "\"}", managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
		JsonNode body = json(response.body());
		assertThat(body.get("branchId").asString()).isEqualTo(branchA1.getId().toString());
		String location = response.headers().firstValue("Location").orElseThrow();
		assertThat(location).endsWith("/api/v1/counters/" + body.get("id").asString());

		assertThat(post("/api/v1/branches/" + branchA1.getId() + "/counters",
				"{\"name\":\"\"}", managerAToken).statusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST.value());
		HttpResponse<String> duplicate = post(
				"/api/v1/branches/" + branchA1.getId() + "/counters",
				"{\"name\":\"Counter 1 " + tag + "\"}", managerAToken);
		assertThat(duplicate.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(json(duplicate.body()).get("code").asString())
				.isEqualTo("COUNTER_NAME_CONFLICT");

		assertThat(post("/api/v1/branches/" + branchA2.getId() + "/counters",
				"{\"name\":\"Counter 1 " + tag + "\"}", managerAToken).statusCode())
				.isEqualTo(HttpStatus.CREATED.value());
		assertThat(post("/api/v1/branches/" + branchA1.getId() + "/counters",
				"{\"name\":\"Staff " + tag + "\"}", staffA1Token).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post("/api/v1/branches/" + branchA1.getId() + "/counters",
				"{\"name\":\"Cust " + tag + "\"}", customerAToken).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	@Test
	void updateCounterAndLifecycle() throws Exception {
		ServiceCounter target =
				counters.save(new ServiceCounter(branchA1, "OldName " + tag));

		HttpResponse<String> renamed = put("/api/v1/counters/" + target.getId(),
				"{\"name\":\"NewName " + tag + "\"}", managerAToken);
		assertThat(renamed.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(renamed.body()).get("name").asString())
				.isEqualTo("NewName " + tag);

		assertThat(put("/api/v1/counters/" + target.getId(),
				"{\"name\":\"Counter 1 " + tag + "\"}", managerAToken).statusCode())
				.isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(put("/api/v1/counters/" + ctrB1.getId(),
				"{\"name\":\"Remote " + tag + "\"}", managerAToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(put("/api/v1/counters/" + target.getId(),
				"{\"name\":\"Staff " + tag + "\"}", staffA1Token).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());

		assertThat(json(post("/api/v1/counters/" + target.getId() + "/deactivate", null,
				managerAToken).body()).get("status").asString()).isEqualTo("INACTIVE");
		assertThat(json(post("/api/v1/counters/" + target.getId() + "/activate", null,
				adminAToken).body()).get("status").asString()).isEqualTo("ACTIVE");
	}

	// -- Capabilities ------------------------------------------------------------------

	@Test
	void capabilityLifecycle() throws Exception {
		String base = "/api/v1/counters/" + ctrA1.getId() + "/capabilities";

		assertThat(capabilityNames(json(get(base, managerAToken).body())).size())
				.isEqualTo(0);

		HttpResponse<String> added = post(base,
				"{\"serviceId\":\"" + svcA1.getId() + "\"}", managerAToken);
		assertThat(added.statusCode()).isEqualTo(HttpStatus.CREATED.value());
		JsonNode body = json(added.body());
		assertThat(UUID.fromString(body.get("serviceId").asString()))
				.isEqualTo(svcA1.getId());
		assertThat(body.get("serviceName").asString()).isEqualTo("Consult " + tag);
		String location = added.headers().firstValue("Location").orElseThrow();
		assertThat(location).endsWith(
				"/api/v1/counters/" + ctrA1.getId() + "/capabilities/" + svcA1.getId());

		List<String> listed = capabilityNames(json(get(base, managerAToken).body()));
		assertThat(listed).containsExactly("Consult " + tag);

		HttpResponse<String> duplicate =
				post(base, "{\"serviceId\":\"" + svcA1.getId() + "\"}", managerAToken);
		assertThat(duplicate.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(json(duplicate.body()).get("code").asString())
				.isEqualTo("CAPABILITY_ALREADY_EXISTS");

		assertThat(delete(base + "/" + svcA1.getId(), managerAToken).statusCode())
				.isEqualTo(HttpStatus.NO_CONTENT.value());
		assertThat(capabilityNames(json(get(base, managerAToken).body())).size())
				.isEqualTo(0);

		HttpResponse<String> missing =
				delete(base + "/" + svcA1.getId(), managerAToken);
		assertThat(missing.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(json(missing.body()).get("code").asString())
				.isEqualTo("CAPABILITY_NOT_FOUND");
	}

	@Test
	void capabilityBranchMismatchIs422() throws Exception {
		String base = "/api/v1/counters/" + ctrA1.getId() + "/capabilities";

		// Same organization, other branch: the invariant, not scope, rejects.
		HttpResponse<String> mismatch = post(base,
				"{\"serviceId\":\"" + svcA2.getId() + "\"}", managerAToken);
		assertThat(mismatch.statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
		assertThat(json(mismatch.body()).get("code").asString())
				.isEqualTo("CAPABILITY_BRANCH_MISMATCH");

		// Foreign organization rows are scope-safe 404s, never the 422.
		assertThat(post(base, "{\"serviceId\":\"" + svcB1.getId() + "\"}",
				managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		ServiceCounter foreign = counters.save(new ServiceCounter(branchB1, "Fx " + tag));
		assertThat(post("/api/v1/counters/" + foreign.getId() + "/capabilities",
				"{\"serviceId\":\"" + svcA1.getId() + "\"}", managerAToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void capabilityReadAndWriteMatrix() throws Exception {
		post("/api/v1/counters/" + ctrA1.getId() + "/capabilities",
				"{\"serviceId\":\"" + svcA1.getId() + "\"}", managerAToken);
		String base = "/api/v1/counters/" + ctrA1.getId() + "/capabilities";

		assertThat(get(base, staffA1Token).statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(get(base, customerAToken).statusCode()).isEqualTo(HttpStatus.OK.value());
		ServiceCounter other = counters.save(new ServiceCounter(branchA2, "Cap9 " + tag));
		assertThat(get("/api/v1/counters/" + other.getId() + "/capabilities",
				staffA1Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());

		assertThat(post(base, "{\"serviceId\":\"" + svcA1.getId() + "\"}",
				staffA1Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post(base, "{\"serviceId\":\"" + svcA1.getId() + "\"}",
				customerAToken).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post(base, "{\"serviceId\":\"" + svcA1.getId() + "\"}",
				null).statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
		assertThat(delete(base + "/" + svcA1.getId(), staffA1Token).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(delete(base + "/" + svcA1.getId(), adminAToken).statusCode())
				.isEqualTo(HttpStatus.NO_CONTENT.value());
	}

	@Test
	void deactivatingCounterKeepsCapabilities() throws Exception {
		post("/api/v1/counters/" + ctrA1.getId() + "/capabilities",
				"{\"serviceId\":\"" + svcA1.getId() + "\"}", managerAToken);
		post("/api/v1/counters/" + ctrA1.getId() + "/deactivate", null, managerAToken);

		List<String> stillThere = capabilityNames(json(
				get("/api/v1/counters/" + ctrA1.getId() + "/capabilities",
						managerAToken).body()));
		assertThat(stillThere).containsExactly("Consult " + tag);

		post("/api/v1/counters/" + ctrA1.getId() + "/activate", null, managerAToken);
	}

	@Test
	void capabilityValidation() throws Exception {
		String base = "/api/v1/counters/" + ctrA1.getId() + "/capabilities";
		assertThat(post(base, "{}", managerAToken).statusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(post(base, "{\"serviceId\":\"" + UUID.randomUUID() + "\"}",
				managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}
}

