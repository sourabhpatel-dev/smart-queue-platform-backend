package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * REST foundation proofs over real HTTP (see ADR-022).
 *
 * <p>Covers the representative organization/branch reads end to end —
 * JWT → principal → tenant scope → policy → service → DTO — plus the
 * shared error envelope, validation failures, the login contract inside
 * the new conventions, JSON representations, and the generated OpenAPI
 * document.
 *
 * <p>Plain {@code java.net.http} against {@code RANDOM_PORT}: true
 * black-box HTTP through the real filter chain with no MockMvc machinery.
 * Non-transactional so the server thread observes committed fixtures.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RestApiFoundationTests {

	static final String PASSWORD = "Rest-Foundation-9";

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

	Organization orgA;
	Branch branchA1;
	Branch branchA2;
	Organization orgB;
	Branch branchB1;

	String managerAToken;
	String staffAToken;
	String customerAToken;
	String managerBToken;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		orgA = organizations.save(new Organization("Rest Org A " + tag));
		branchA1 = branches.save(new Branch(orgA, "Downtown " + tag,
				"Indore", "India", ZoneId.of("Asia/Kolkata")));
		branchA2 = branches.save(new Branch(orgA, "Uptown " + tag,
				"Bhopal", "India", ZoneId.of("Asia/Kolkata")));
		orgB = organizations.save(new Organization("Rest Org B " + tag));
		branchB1 = branches.save(new Branch(orgB, "Riverside " + tag,
				"Pune", "India", ZoneId.of("Asia/Kolkata")));

		managerAToken = tokenFor(newUser(orgA, "mgr-a-" + tag, RoleType.MANAGER));
		User staffA = newUser(orgA, "staff-a-" + tag, RoleType.STAFF);
		assignments.save(new StaffAssignment(orgA, staffA, branchA1));
		staffAToken = tokenFor(staffA);
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
				.POST(HttpRequest.BodyPublishers.ofString(body));
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

	private void assertEnvelope(JsonNode body, int status, String code, String path) {
		assertThat(body.get("timestamp").asString()).isNotBlank();
		assertThat(Instant.parse(body.get("timestamp").asString())).isBeforeOrEqualTo(
				Instant.now().plusSeconds(60));
		assertThat(body.get("status").asInt()).isEqualTo(status);
		assertThat(body.get("code").asString()).isEqualTo(code);
		assertThat(body.get("message").asString()).isNotBlank();
		assertThat(body.get("path").asString()).isEqualTo(path);
		assertThat(body.toString()).doesNotContain("passwordHash");
		assertThat(body.toString()).doesNotContain("BatchUpdateException");
	}

	// -- Organization reads ------------------------------------------------

	@Test
	void ownOrganizationReadsSuccessfully() throws Exception {
		HttpResponse<String> response =
				get("/api/v1/organizations/" + orgA.getId(), managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(UUID.fromString(body.get("id").asString())).isEqualTo(orgA.getId());
		assertThat(body.get("name").asString()).isEqualTo(orgA.getName());
		assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(Instant.parse(body.get("createdAt").asString())).isNotNull();
		assertThat(Instant.parse(body.get("updatedAt").asString())).isNotNull();
		assertThat(response.body()).doesNotContain("passwordHash");
	}

	@Test
	void foreignOrganizationIsScopeSafeNotFound() throws Exception {
		String path = "/api/v1/organizations/" + orgB.getId();
		HttpResponse<String> foreign = get(path, managerAToken);

		assertThat(foreign.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		JsonNode body = json(foreign.body());
		assertEnvelope(body, 404, "ORGANIZATION_NOT_FOUND", path);

		// Indistinguishable from a genuinely unknown id: no enumeration.
		String unknownPath = "/api/v1/organizations/" + UUID.randomUUID();
		HttpResponse<String> unknown = get(unknownPath, managerAToken);
		assertThat(unknown.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(json(unknown.body()).get("code").asString())
				.isEqualTo("ORGANIZATION_NOT_FOUND");
		assertThat(json(unknown.body()).get("message").asString())
				.isEqualTo(body.get("message").asString());
	}

	@Test
	void organizationRequiresAuthentication() throws Exception {
		assertThat(get("/api/v1/organizations/" + orgA.getId(), null).statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());
		assertThat(get("/api/v1/organizations/" + orgA.getId(), "not-a-token").statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	@Test
	void malformedOrganizationIdIs400() throws Exception {
		String path = "/api/v1/organizations/not-a-uuid";
		HttpResponse<String> response = get(path, managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertEnvelope(json(response.body()), 400, "INVALID_REQUEST", path);
	}

	// -- Branch reads -------------------------------------------------------

	@Test
	void managerReadsOwnBranch() throws Exception {
		HttpResponse<String> response =
				get("/api/v1/branches/" + branchA1.getId(), managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(UUID.fromString(body.get("id").asString()))
				.isEqualTo(branchA1.getId());
		assertThat(UUID.fromString(body.get("organizationId").asString()))
				.isEqualTo(orgA.getId());
		assertThat(body.get("name").asString()).isEqualTo(branchA1.getName());
		assertThat(body.get("city").asString()).isEqualTo("Indore");
		assertThat(body.get("country").asString()).isEqualTo("India");
		assertThat(body.get("timeZone").asString()).isEqualTo("Asia/Kolkata");
		assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(Instant.parse(body.get("createdAt").asString())).isNotNull();
	}

	@Test
	void staffReadsAssignedBranchButNotUnassigned() throws Exception {
		assertThat(get("/api/v1/branches/" + branchA1.getId(), staffAToken).statusCode())
				.isEqualTo(HttpStatus.OK.value());

		String path = "/api/v1/branches/" + branchA2.getId();
		HttpResponse<String> denied = get(path, staffAToken);
		assertThat(denied.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertEnvelope(json(denied.body()), 403, "FORBIDDEN", path);
	}

	@Test
	void customerObservesOwnOrganizationBranch() throws Exception {
		assertThat(get("/api/v1/branches/" + branchA1.getId(), customerAToken).statusCode())
				.isEqualTo(HttpStatus.OK.value());
	}

	@Test
	void foreignBranchIsScopeSafeNotFound() throws Exception {
		// IDOR proof: knowing branch B's UUID buys org A's manager nothing.
		String path = "/api/v1/branches/" + branchB1.getId();
		HttpResponse<String> foreign = get(path, managerAToken);

		assertThat(foreign.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertEnvelope(json(foreign.body()), 404, "BRANCH_NOT_FOUND", path);

		// And org B's own manager can read it — the row exists, scope hid it.
		assertThat(get(path, managerBToken).statusCode())
				.isEqualTo(HttpStatus.OK.value());

		String unknownPath = "/api/v1/branches/" + UUID.randomUUID();
		HttpResponse<String> unknown = get(unknownPath, managerAToken);
		assertThat(unknown.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(json(unknown.body()).get("code").asString())
				.isEqualTo("BRANCH_NOT_FOUND");
	}

	@Test
	void branchRequiresAuthentication() throws Exception {
		assertThat(get("/api/v1/branches/" + branchA1.getId(), null).statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	// -- Login inside the new conventions -----------------------------------

	@Test
	void loginSuccessAndFailuresUseSharedEnvelope() throws Exception {
		String email = "envelope-" + UUID.randomUUID() + "@example.com";
		User user = users.save(new User(orgA, email, "Envelope"));
		user.grantRole(RoleType.STAFF);
		user = users.save(user);
		credentials.setPassword(user.getId(), PASSWORD);

		HttpResponse<String> ok = post("/api/v1/auth/login",
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", null);
		assertThat(ok.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(ok.body()).get("accessToken").asString()).isNotBlank();

		HttpResponse<String> badPassword = post("/api/v1/auth/login",
				"{\"email\":\"" + email + "\",\"password\":\"Wrong-Password-1\"}", null);
		assertThat(badPassword.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
		JsonNode denied = json(badPassword.body());
		assertEnvelope(denied, 401, "UNAUTHENTICATED", "/api/v1/auth/login");
		assertThat(denied.get("message").asString())
				.isEqualTo(AuthService.GENERIC_FAILURE);
	}

	@Test
	void loginValidationFailureCarriesFieldErrors() throws Exception {
		HttpResponse<String> response = post("/api/v1/auth/login",
				"{\"email\":\"not-an-email\",\"password\":\"" + PASSWORD + "\"}", null);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		JsonNode body = json(response.body());
		assertEnvelope(body, 400, "VALIDATION_ERROR", "/api/v1/auth/login");
		assertThat(body.get("fieldErrors").size()).isGreaterThan(0);
		assertThat(body.get("fieldErrors").get(0).get("field").asString()).isNotBlank();
		assertThat(body.get("fieldErrors").get(0).get("message").asString()).isNotBlank();
	}

	@Test
	void unknownJsonPropertiesAreIgnored() throws Exception {
		String email = "lenient-" + UUID.randomUUID() + "@example.com";
		User user = users.save(new User(orgA, email, "Lenient"));
		user.grantRole(RoleType.STAFF);
		user = users.save(user);
		credentials.setPassword(user.getId(), PASSWORD);

		// Jackson policy probe: extra properties must not break the contract.
		HttpResponse<String> response = post("/api/v1/auth/login",
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
						+ "\",\"organizationId\":\"" + UUID.randomUUID() + "\"}",
				null);
		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
	}

	@Test
	void authenticatedUnknownPathStays404Envelope() throws Exception {
		String path = "/api/v1/queues";
		HttpResponse<String> response = get(path, managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertEnvelope(json(response.body()), 404, "NOT_FOUND", path);
	}

	// -- OpenAPI --------------------------------------------------------------

	@Test
	void openApiDocumentCoversContractAndSecurity() throws Exception {
		HttpResponse<String> response = get("/v3/api-docs", null);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode doc = json(response.body());
		assertThat(doc.get("info").get("title").asString()).isNotBlank();
		assertThat(doc.get("components").get("securitySchemes")
				.get("bearerAuth").get("scheme").asString()).isEqualTo("bearer");

		JsonNode paths = doc.get("paths");
		assertThat(paths.get("/api/v1/auth/login")).isNotNull();
		assertThat(paths.get("/api/v1/organizations/{organizationId}")).isNotNull();
		assertThat(paths.get("/api/v1/branches/{branchId}")).isNotNull();

		// Login renders unlocked; the representative reads require bearerAuth.
		assertThat(paths.get("/api/v1/auth/login").get("post").get("security")).isNull();
		assertThat(paths.get("/api/v1/organizations/{organizationId}")
				.get("get").get("security").toString()).contains("bearerAuth");
		assertThat(paths.get("/api/v1/branches/{branchId}")
				.get("get").get("security").toString()).contains("bearerAuth");
	}

	@Test
	void swaggerUiIsServed() throws Exception {
		assertThat(get("/swagger-ui/index.html", null).statusCode())
				.isEqualTo(HttpStatus.OK.value());
	}

	@Test
	void healthStaysPublic() throws Exception {
		assertThat(get("/actuator/health", null).statusCode())
				.isEqualTo(HttpStatus.OK.value());
	}
}

