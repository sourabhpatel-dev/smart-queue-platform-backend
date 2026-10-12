package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.staff.StaffAssignment;
import com.sourabh.smartqueue.staff.StaffAssignmentRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * User/staff-assignment API proofs over real HTTP (see ADR-025).
 *
 * <p>Role matrix, both cross-tenant directions, roster visibility tiers,
 * self-or-manager reads, pagination/sort/filter edges, constraint-
 * arbitrated conflicts, lifecycle idempotency, and the authentication
 * integration (create → login → deactivate → denied → reactivate →
 * login). Non-transactional; dedicated fixtures where exactness matters
 * so no test depends on another's rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class UserStaffApiTests {

	static final String PASSWORD = "User-Staff-14";
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
	User managerA;
	User staffA1;
	User customerA;
	User managerB;

	String managerAToken;
	String adminAToken;
	String staffA1Token;
	String customerAToken;
	String managerBToken;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		tag = UUID.randomUUID().toString().substring(0, 8);
		orgA = organizations.save(new Organization("User Org A " + tag));
		branchA1 = branches.save(new Branch(orgA, "Downtown " + tag,
				"Indore", "India", KOLKATA));
		branchA2 = branches.save(new Branch(orgA, "Uptown " + tag,
				"Bhopal", "India", KOLKATA));
		orgB = organizations.save(new Organization("User Org B " + tag));
		branchB1 = branches.save(new Branch(orgB, "Riverside " + tag,
				"Pune", "India", KOLKATA));

		managerA = newUser(orgA, "mgr-a-" + tag, RoleType.MANAGER);
		newUser(orgA, "adm-a-" + tag, RoleType.ADMIN);
		staffA1 = newUser(orgA, "staff-a1-" + tag, RoleType.STAFF);
		assignments.save(new StaffAssignment(orgA, staffA1, branchA1));
		customerA = newUser(orgA, "cust-a-" + tag, RoleType.CUSTOMER);
		managerB = newUser(orgB, "mgr-b-" + tag, RoleType.MANAGER);

		managerAToken = tokenFor(managerA);
		adminAToken = tokenFor(users.findByEmail("adm-a-" + tag + "@example.com").orElseThrow());
		staffA1Token = tokenFor(staffA1);
		customerAToken = tokenFor(customerA);
		managerBToken = tokenFor(managerB);
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

	private JsonNode json(String body) {
		try {
			return objectMapper.readTree(body);
		} catch (tools.jackson.core.JacksonException e) {
			throw new IllegalStateException("unreadable JSON response: " + body, e);
		}
	}

	private HttpResponse<String> login(String email, String password) throws Exception {
		return post("/api/v1/auth/login",
				"{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", null);
	}

	private List<String> contentEmails(JsonNode page) {
		List<String> emails = new ArrayList<>();
		page.get("content").forEach(node -> emails.add(node.get("email").asString()));
		return emails;
	}

	// -- User list --------------------------------------------------------------------

	@Test
	void listUsersIsManagementOnly() throws Exception {
		HttpResponse<String> response = get("/api/v1/users", managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode page = json(response.body());
		assertThat(page.get("page").asInt()).isEqualTo(0);
		List<String> emails = contentEmails(page);
		assertThat(emails).contains(managerA.getEmail(), staffA1.getEmail());
		assertThat(emails).doesNotContain(managerB.getEmail());
		assertThat(response.body()).doesNotContain("passwordHash");

		assertThat(get("/api/v1/users", adminAToken).statusCode())
				.isEqualTo(HttpStatus.OK.value());
		assertThat(get("/api/v1/users", staffA1Token).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(get("/api/v1/users", customerAToken).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(get("/api/v1/users", null).statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	@Test
	void listUsersPaginationSortingAndFilters() throws Exception {
		Organization org = organizations.save(new Organization("Paged Users " + tag));
		String token = tokenFor(newUser(org, "mgr-p-" + tag, RoleType.MANAGER));
		for (String local : List.of("zebra", "mike", "anna", "john", "zoe")) {
			newUser(org, local + "-" + tag, RoleType.CUSTOMER);
		}

		JsonNode first = json(get("/api/v1/users?page=0&size=2", token).body());
		assertThat(first.get("content").size()).isEqualTo(2);
		assertThat(first.get("totalElements").asLong()).isEqualTo(6);
		assertThat(first.get("totalPages").asInt()).isEqualTo(3);
		JsonNode beyond = json(get("/api/v1/users?page=9&size=2", token).body());
		assertThat(beyond.get("content").size()).isEqualTo(0);

		List<String> asc = contentEmails(json(
				get("/api/v1/users?sort=email,asc&size=100", token).body()));
		List<String> sorted = new ArrayList<>(asc);
		sorted.sort(String::compareTo);
		assertThat(asc).isEqualTo(sorted);

		JsonNode staffOnly = json(
				get("/api/v1/users?role=STAFF&size=100", token).body());
		assertThat(staffOnly.get("totalElements").asLong()).isEqualTo(0);
		HttpResponse<String> badRole = get("/api/v1/users?role=BOGUS", token);
		assertThat(badRole.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(json(badRole.body()).get("code").asString()).isEqualTo("INVALID_ROLE");

		HttpResponse<String> badSort = get("/api/v1/users?sort=roles,asc", token);
		assertThat(badSort.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(get("/api/v1/users?size=101", token).statusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST.value());
	}

	@Test
	void listUsersStatusFilter() throws Exception {
		User retiring = newUser(orgA, "retire-" + tag, RoleType.CUSTOMER);
		post("/api/v1/users/" + retiring.getId() + "/deactivate", null, managerAToken);

		List<String> inactive = contentEmails(json(
				get("/api/v1/users?status=INACTIVE&size=100", managerAToken).body()));
		assertThat(inactive).contains(retiring.getEmail());
		List<String> active = contentEmails(json(
				get("/api/v1/users?status=ACTIVE&size=100", managerAToken).body()));
		assertThat(active).doesNotContain(retiring.getEmail());
		assertThat(get("/api/v1/users?status=BOGUS", managerAToken).statusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST.value());
	}

	// -- User item -----------------------------------------------------------------------

	@Test
	void getUserSelfOrManager() throws Exception {
		HttpResponse<String> self = get("/api/v1/users/" + staffA1.getId(), staffA1Token);
		assertThat(self.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(self.body());
		assertThat(body.get("email").asString()).isEqualTo(staffA1.getEmail());
		assertThat(body.get("displayName").asString()).isEqualTo(staffA1.getDisplayName());
		assertThat(body.get("organizationId").asString())
				.isEqualTo(orgA.getId().toString());
		assertThat(body.get("roles").toString()).contains("STAFF");
		assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(self.body()).doesNotContain("passwordHash");

		assertThat(get("/api/v1/users/" + staffA1.getId(), managerAToken).statusCode())
				.isEqualTo(HttpStatus.OK.value());
		assertThat(get("/api/v1/users/" + staffA1.getId(), customerAToken).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(get("/api/v1/users/" + managerA.getId(), staffA1Token).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	@Test
	void getUserCrossTenantIs404() throws Exception {
		assertThat(get("/api/v1/users/" + managerB.getId(), managerAToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/users/" + managerA.getId(), managerBToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/users/" + UUID.randomUUID(), managerAToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(json(get("/api/v1/users/" + managerB.getId(), managerAToken).body())
				.get("code").asString()).isEqualTo("USER_NOT_FOUND");
	}

	// -- User writes ------------------------------------------------------------------------

	@Test
	void managerCreatesUserWithDefaults() throws Exception {
		HttpResponse<String> response = post("/api/v1/users",
				"{\"email\":\"new-" + tag + "@example.com\",\"displayName\":\"New Hire\"}",
				managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
		JsonNode body = json(response.body());
		assertThat(body.get("email").asString()).isEqualTo("new-" + tag + "@example.com");
		assertThat(body.get("organizationId").asString())
				.isEqualTo(orgA.getId().toString());
		assertThat(body.get("roles").toString()).contains("CUSTOMER");
		assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
		String location = response.headers().firstValue("Location").orElseThrow();
		assertThat(location).endsWith("/api/v1/users/" + body.get("id").asString());
		assertThat(response.body()).doesNotContain("passwordHash");

		// Credential-less users fail login generically (no enumeration signal).
		assertThat(login("new-" + tag + "@example.com", PASSWORD).statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	@Test
	void createUserWithPasswordAndRoles() throws Exception {
		HttpResponse<String> response = post("/api/v1/users",
				"{\"email\":\"hired-" + tag + "@example.com\",\"displayName\":\"Hired\","
						+ "\"roles\":[\"STAFF\"],\"password\":\"Initial-Pass-1\"}",
				adminAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
		assertThat(json(response.body()).get("roles").toString()).contains("STAFF");
		assertThat(response.body()).doesNotContain("Initial-Pass-1");

		// The provisioned credential authenticates immediately.
		assertThat(login("hired-" + tag + "@example.com", "Initial-Pass-1").statusCode())
				.isEqualTo(HttpStatus.OK.value());
	}

	@Test
	void userWriteMatrix() throws Exception {
		String payload =
				"{\"email\":\"denied-" + tag + "@example.com\",\"displayName\":\"Denied\"}";
		assertThat(post("/api/v1/users", payload, staffA1Token).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post("/api/v1/users", payload, customerAToken).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post("/api/v1/users", payload, null).statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());
		assertThat(put("/api/v1/users/" + staffA1.getId(),
				"{\"displayName\":\"Self\",\"roles\":[\"STAFF\"]}", staffA1Token).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post("/api/v1/users/" + staffA1.getId() + "/deactivate", null,
				customerAToken).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	@Test
	void duplicateEmailConflictsGlobally() throws Exception {
		String payload = "{\"email\":\"" + staffA1.getEmail()
				+ "\",\"displayName\":\"Clone\"}";
		HttpResponse<String> duplicate = post("/api/v1/users", payload, managerAToken);
		assertThat(duplicate.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(json(duplicate.body()).get("code").asString())
				.isEqualTo("USER_EMAIL_CONFLICT");
		assertThat(duplicate.body()).doesNotContain("uq_user_email");

		// Normalization folds case variants into the same conflict.
		assertThat(post("/api/v1/users",
				"{\"email\":\"" + staffA1.getEmail().toUpperCase()
						+ "\",\"displayName\":\"Clone\"}",
				managerAToken).statusCode()).isEqualTo(HttpStatus.CONFLICT.value());

		// Global uniqueness spans organizations.
		newUser(orgB, "shared-" + tag, RoleType.CUSTOMER);
		assertThat(post("/api/v1/users",
				"{\"email\":\"shared-" + tag + "@example.com\",\"displayName\":\"Shared\"}",
				managerAToken).statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
	}

	@Test
	void createUserValidation() throws Exception {
		assertThat(post("/api/v1/users",
				"{\"email\":\"bad\",\"displayName\":\"Bad\"}", managerAToken).statusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(post("/api/v1/users",
				"{\"email\":\"blank-" + tag + "@example.com\",\"displayName\":\"\"}",
				managerAToken).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(post("/api/v1/users",
				"{\"email\":\"short-" + tag + "@example.com\",\"displayName\":\"Short\","
						+ "\"password\":\"tiny\"}",
				managerAToken).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		HttpResponse<String> badRole = post("/api/v1/users",
				"{\"email\":\"role-" + tag + "@example.com\",\"displayName\":\"Role\","
						+ "\"roles\":[\"SUPERUSER\"]}",
				managerAToken);
		assertThat(badRole.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(json(badRole.body()).get("code").asString())
				.isEqualTo("INVALID_USER_REQUEST");
	}

	@Test
	void adminGrantStaysOrganizationScoped() throws Exception {
		HttpResponse<String> response = post("/api/v1/users",
				"{\"email\":\"orgadmin-" + tag + "@example.com\",\"displayName\":\"Org Admin\","
						+ "\"roles\":[\"ADMIN\"]}",
				managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
		assertThat(json(response.body()).get("organizationId").asString())
				.isEqualTo(orgA.getId().toString());
		// The grant is org-scoped by construction: no platform admin concept
		// exists, and the new admin still cannot see the other organization.
		User orgAdmin = users.findByEmail("orgadmin-" + tag + "@example.com").orElseThrow();
		credentials.setPassword(orgAdmin.getId(), PASSWORD);
		String adminToken = tokenFor(orgAdmin);
		assertThat(get("/api/v1/users/" + managerB.getId(), adminToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void updateUserSemantics() throws Exception {
		User target = newUser(orgA, "editable-" + tag, RoleType.CUSTOMER);
		HttpResponse<String> response = put("/api/v1/users/" + target.getId(),
				"{\"displayName\":\"Edited\",\"roles\":[\"STAFF\",\"CUSTOMER\"]}",
				managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(body.get("displayName").asString()).isEqualTo("Edited");
		assertThat(body.get("roles").toString()).contains("STAFF");

		// Email is identity: supplied values are ignored, never applied.
		HttpResponse<String> pinned = put("/api/v1/users/" + target.getId(),
				"{\"displayName\":\"Edited\",\"roles\":[],\"email\":\"moved@example.com\","
						+ "\"organizationId\":\"" + orgB.getId() + "\",\"status\":\"INACTIVE\"}",
				managerAToken);
		assertThat(pinned.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(pinned.body()).get("email").asString())
				.isEqualTo("editable-" + tag + "@example.com");
		assertThat(json(pinned.body()).get("organizationId").asString())
				.isEqualTo(orgA.getId().toString());
		assertThat(json(pinned.body()).get("status").asString()).isEqualTo("ACTIVE");

		assertThat(put("/api/v1/users/" + managerB.getId(),
				"{\"displayName\":\"Remote\",\"roles\":[]}", managerAToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void userLifecycleIntegratesWithLogin() throws Exception {
		String email = "cycling-" + tag + "@example.com";
		JsonNode created = json(post("/api/v1/users",
				"{\"email\":\"" + email + "\",\"displayName\":\"Cycling\","
						+ "\"password\":\"Cycle-Pass-1\"}",
				managerAToken).body());
		String id = created.get("id").asString();

		assertThat(login(email, "Cycle-Pass-1").statusCode())
				.isEqualTo(HttpStatus.OK.value());

		assertThat(json(post("/api/v1/users/" + id + "/deactivate", null,
				managerAToken).body()).get("status").asString()).isEqualTo("INACTIVE");
		assertThat(post("/api/v1/users/" + id + "/deactivate", null, managerAToken)
				.statusCode()).isEqualTo(HttpStatus.OK.value());

		// Deactivated: login fails generically (no credential signal).
		HttpResponse<String> denied = login(email, "Cycle-Pass-1");
		assertThat(denied.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
		assertThat(json(denied.body()).get("code").asString())
				.isEqualTo("UNAUTHENTICATED");

		assertThat(json(post("/api/v1/users/" + id + "/activate", null, adminAToken)
				.body()).get("status").asString()).isEqualTo("ACTIVE");
		assertThat(login(email, "Cycle-Pass-1").statusCode())
				.isEqualTo(HttpStatus.OK.value());
	}

	// -- Assignments --------------------------------------------------------------------------

	@Test
	void createAssignmentSemantics() throws Exception {
		User newcomer = newUser(orgA, "rookie-" + tag, RoleType.STAFF);
		HttpResponse<String> response = post(
				"/api/v1/users/" + newcomer.getId() + "/staff-assignments",
				"{\"branchId\":\"" + branchA2.getId() + "\"}", managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
		JsonNode body = json(response.body());
		assertThat(body.get("userId").asString()).isEqualTo(newcomer.getId().toString());
		assertThat(body.get("branchId").asString()).isEqualTo(branchA2.getId().toString());
		assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
		String location = response.headers().firstValue("Location").orElseThrow();
		assertThat(location).endsWith("/api/v1/staff-assignments/" + body.get("id").asString());

		// The unique key ignores status-blind duplicates: 409, reactivate instead.
		HttpResponse<String> duplicate = post(
				"/api/v1/users/" + staffA1.getId() + "/staff-assignments",
				"{\"branchId\":\"" + branchA1.getId() + "\"}", managerAToken);
		assertThat(duplicate.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(json(duplicate.body()).get("code").asString())
				.isEqualTo("STAFF_ASSIGNMENT_CONFLICT");
		assertThat(duplicate.body()).doesNotContain("uq_staff_user_branch");
	}

	@Test
	void assignmentWriteMatrix() throws Exception {
		User newcomer = newUser(orgA, "guarded-" + tag, RoleType.STAFF);
		String path = "/api/v1/users/" + newcomer.getId() + "/staff-assignments";
		String payload = "{\"branchId\":\"" + branchA1.getId() + "\"}";
		assertThat(post(path, payload, staffA1Token).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post(path, payload, customerAToken).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post(path, payload, null).statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	@Test
	void assignmentRequiresStaffRole() throws Exception {
		HttpResponse<String> response = post(
				"/api/v1/users/" + customerA.getId() + "/staff-assignments",
				"{\"branchId\":\"" + branchA1.getId() + "\"}", managerAToken);
		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(json(response.body()).get("code").asString())
				.isEqualTo("INVALID_USER_REQUEST");
	}

	@Test
	void assignmentCrossTenantIs404() throws Exception {
		// Foreign user, foreign branch, and cross-pair — all scope-safe 404s.
		assertThat(post("/api/v1/users/" + managerB.getId() + "/staff-assignments",
				"{\"branchId\":\"" + branchA1.getId() + "\"}", managerAToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(post("/api/v1/users/" + staffA1.getId() + "/staff-assignments",
				"{\"branchId\":\"" + branchB1.getId() + "\"}", managerAToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(post("/api/v1/users/" + UUID.randomUUID() + "/staff-assignments",
				"{\"branchId\":\"" + branchA1.getId() + "\"}", managerAToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(post("/api/v1/users/" + staffA1.getId() + "/staff-assignments",
				"{\"branchId\":\"" + branchA1.getId() + "\"}", managerBToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void listUserAssignmentsSelfOrManager() throws Exception {
		HttpResponse<String> own = get(
				"/api/v1/users/" + staffA1.getId() + "/staff-assignments", staffA1Token);
		assertThat(own.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(own.body()).get("totalElements").asLong()).isEqualTo(1);

		assertThat(get("/api/v1/users/" + staffA1.getId() + "/staff-assignments",
				managerAToken).statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(get("/api/v1/users/" + managerA.getId() + "/staff-assignments",
				staffA1Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(get("/api/v1/users/" + staffA1.getId() + "/staff-assignments",
				customerAToken).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(get("/api/v1/users/" + managerB.getId() + "/staff-assignments",
				managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void listBranchStaffRosterTiers() throws Exception {
		HttpResponse<String> response =
				get("/api/v1/branches/" + branchA1.getId() + "/staff", managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode page = json(response.body());
		assertThat(page.get("totalElements").asLong()).isGreaterThanOrEqualTo(1);
		JsonNode first = page.get("content").get(0);
		assertThat(first.get("assignmentId").asString()).isNotBlank();
		assertThat(first.get("email").asString()).isEqualTo(staffA1.getEmail());
		assertThat(first.get("roles").toString()).contains("STAFF");
		assertThat(first.get("assignmentStatus").asString()).isEqualTo("ACTIVE");
		assertThat(response.body()).doesNotContain("passwordHash");

		assertThat(get("/api/v1/branches/" + branchA1.getId() + "/staff",
				staffA1Token).statusCode()).isEqualTo(HttpStatus.OK.value());
		// Assigned to A1 only: A2's roster is forbidden.
		assertThat(get("/api/v1/branches/" + branchA2.getId() + "/staff",
				staffA1Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		// Customers never enumerate rosters.
		assertThat(get("/api/v1/branches/" + branchA1.getId() + "/staff",
				customerAToken).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(get("/api/v1/branches/" + branchB1.getId() + "/staff",
				managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/branches/" + branchA1.getId() + "/staff", null)
				.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	@Test
	void branchStaffPaginationAndFilter() throws Exception {
		Branch branch = branches.save(new Branch(orgA, "Roster " + tag,
				"Indore", "India", KOLKATA));
		for (String local : List.of("ra-", "rb-", "rc-")) {
			User member = newUser(orgA, local + tag, RoleType.STAFF);
			assignments.save(new StaffAssignment(orgA, member, branch));
		}

		JsonNode first = json(get("/api/v1/branches/" + branch.getId()
				+ "/staff?page=0&size=2", managerAToken).body());
		assertThat(first.get("content").size()).isEqualTo(2);
		assertThat(first.get("totalElements").asLong()).isEqualTo(3);
		JsonNode beyond = json(get("/api/v1/branches/" + branch.getId()
				+ "/staff?page=9&size=2", managerAToken).body());
		assertThat(beyond.get("content").size()).isEqualTo(0);

		List<String> emails = new ArrayList<>();
		json(get("/api/v1/branches/" + branch.getId() + "/staff?sort=email,asc&size=100",
				managerAToken).body()).get("content")
				.forEach(node -> emails.add(node.get("email").asString()));
		List<String> sorted = new ArrayList<>(emails);
		sorted.sort(String::compareTo);
		assertThat(emails).isEqualTo(sorted);
		assertThat(get("/api/v1/branches/" + branch.getId() + "/staff?sort=nope",
				managerAToken).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
	}

	@Test
	void assignmentLifecycleAndAccessEffect() throws Exception {
		User temp = newUser(orgA, "temp-" + tag, RoleType.STAFF);
		JsonNode created = json(post(
				"/api/v1/users/" + temp.getId() + "/staff-assignments",
				"{\"branchId\":\"" + branchA2.getId() + "\"}", managerAToken).body());
		String assignmentId = created.get("id").asString();
		String tempToken = tokenFor(temp);

		// Fresh assignment grants branch access through the policy.
		assertThat(get("/api/v1/branches/" + branchA2.getId(), tempToken).statusCode())
				.isEqualTo(HttpStatus.OK.value());

		assertThat(json(post("/api/v1/staff-assignments/" + assignmentId + "/deactivate",
				null, managerAToken).body()).get("status").asString())
				.isEqualTo("INACTIVE");
		assertThat(post("/api/v1/staff-assignments/" + assignmentId + "/deactivate",
				null, managerAToken).statusCode()).isEqualTo(HttpStatus.OK.value());

		// Deactivation revokes the branch access (row preserved, still listed).
		assertThat(get("/api/v1/branches/" + branchA2.getId(), tempToken).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		JsonNode listed = json(get("/api/v1/users/" + temp.getId() + "/staff-assignments",
				managerAToken).body());
		assertThat(listed.get("totalElements").asLong()).isEqualTo(1);

		// Reactivation restores access — no second row (unique key ignores status).
		assertThat(json(post("/api/v1/staff-assignments/" + assignmentId + "/activate",
				null, adminAToken).body()).get("status").asString()).isEqualTo("ACTIVE");
		assertThat(get("/api/v1/branches/" + branchA2.getId(), tempToken).statusCode())
				.isEqualTo(HttpStatus.OK.value());
		assertThat(json(get("/api/v1/users/" + temp.getId() + "/staff-assignments",
				managerAToken).body()).get("totalElements").asLong()).isEqualTo(1);
	}

	@Test
	void assignmentItemScopeAndRole() throws Exception {
		String id = assignments.findByUserIdAndBranchId(staffA1.getId(), branchA1.getId())
				.orElseThrow().getId().toString();

		assertThat(get("/api/v1/staff-assignments/" + id, managerAToken).statusCode())
				.isEqualTo(HttpStatus.OK.value());
		assertThat(get("/api/v1/staff-assignments/" + id, staffA1Token).statusCode())
				.isEqualTo(HttpStatus.OK.value());
		assertThat(get("/api/v1/staff-assignments/" + id, customerAToken).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(get("/api/v1/staff-assignments/" + id, managerBToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(post("/api/v1/staff-assignments/" + id + "/deactivate", null,
				staffA1Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post("/api/v1/staff-assignments/" + UUID.randomUUID() + "/deactivate",
				null, managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}
}

