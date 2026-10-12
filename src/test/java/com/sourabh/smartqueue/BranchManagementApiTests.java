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
 * Branch management API proofs over real HTTP (see ADR-023).
 *
 * <p>Every role in the authorization matrix, both cross-tenant directions,
 * staff assignment boundaries, pagination/sorting/filter edges, conflict
 * translation, and the create/update/status semantics — all through the
 * real filter chain with no MockMvc machinery. Non-transactional so the
 * server thread observes committed fixtures; ordering-sensitive tests use
 * dedicated organizations so no test depends on another's rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class BranchManagementApiTests {

	static final String PASSWORD = "Branch-Mgmt-11";
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
	Branch branchAlpha;
	Branch branchBeta;
	Branch branchGamma;
	Organization orgB;
	Branch branchDelta;

	String managerAToken;
	String adminAToken;
	String staffAToken;
	String customerAToken;
	String managerBToken;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		tag = UUID.randomUUID().toString().substring(0, 8);
		orgA = organizations.save(new Organization("Mgmt Org A " + tag));
		branchAlpha = branch(orgA, "Alpha " + tag);
		branchBeta = branch(orgA, "Beta " + tag);
		branchGamma = branch(orgA, "Gamma " + tag);
		orgB = organizations.save(new Organization("Mgmt Org B " + tag));
		branchDelta = branch(orgB, "Delta " + tag);

		managerAToken = tokenFor(newUser(orgA, "mgr-a-" + tag, RoleType.MANAGER));
		adminAToken = tokenFor(newUser(orgA, "adm-a-" + tag, RoleType.ADMIN));
		User staffA = newUser(orgA, "staff-a-" + tag, RoleType.STAFF);
		assignments.save(new StaffAssignment(orgA, staffA, branchAlpha));
		staffAToken = tokenFor(staffA);
		customerAToken = tokenFor(newUser(orgA, "cust-a-" + tag, RoleType.CUSTOMER));
		managerBToken = tokenFor(newUser(orgB, "mgr-b-" + tag, RoleType.MANAGER));
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private Branch branch(Organization org, String name) {
		return branches.save(new Branch(org, name, "Indore", "India", KOLKATA));
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

	private String branchPayload(String name) {
		return "{\"name\":\"" + name + "\",\"city\":\"Indore\",\"country\":\"India\","
				+ "\"timeZone\":\"Asia/Kolkata\"}";
	}

	private List<String> contentNames(JsonNode page) {
		List<String> names = new ArrayList<>();
		page.get("content").forEach(node -> names.add(node.get("name").asString()));
		return names;
	}

	// -- List: scope ---------------------------------------------------------

	@Test
	void listReturnsOwnOrganizationBranchesOnly() throws Exception {
		HttpResponse<String> response = get("/api/v1/branches", managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode page = json(response.body());
		assertThat(page.get("page").asInt()).isEqualTo(0);
		assertThat(page.get("size").asInt()).isEqualTo(20);
		assertThat(page.get("totalElements").asLong()).isGreaterThanOrEqualTo(3);
		List<String> names = contentNames(page);
		assertThat(names).contains("Alpha " + tag, "Beta " + tag, "Gamma " + tag);
		assertThat(names).doesNotContain("Delta " + tag);
		assertThat(response.body()).doesNotContain("passwordHash");
	}

	@Test
	void listIsOrganizationScopedBothWays() throws Exception {
		List<String> namesB = contentNames(json(
				get("/api/v1/branches", managerBToken).body()));
		assertThat(namesB).contains("Delta " + tag);
		assertThat(namesB).doesNotContain("Alpha " + tag);
	}

	@Test
	void listEmptyOrganizationIsEmptyPage() throws Exception {
		Organization empty = organizations.save(new Organization("Empty " + tag));
		String token = tokenFor(newUser(empty, "mgr-e-" + tag, RoleType.MANAGER));

		HttpResponse<String> response = get("/api/v1/branches", token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode page = json(response.body());
		assertThat(page.get("content").size()).isEqualTo(0);
		assertThat(page.get("totalElements").asLong()).isEqualTo(0);
		assertThat(page.get("totalPages").asInt()).isEqualTo(0);
	}

	@Test
	void listRequiresAuthentication() throws Exception {
		assertThat(get("/api/v1/branches", null).statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	@Test
	void customerCanListOwnOrganization() throws Exception {
		// ADR-040: platform-level customers hold no organization, so the
		// organization-scoped listing is honestly empty for them (200 with
		// zero rows, never a leak of organization contents). Customers
		// discover branches through the public directory instead.
		HttpResponse<String> response = get("/api/v1/branches", customerAToken);
		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(response.body()).get("totalElements").asLong()).isZero();
	}

	// -- List: pagination / sorting / filtering --------------------------------

	@Test
	void paginationWalksFirstMiddleAndBeyond() throws Exception {
		Organization org = organizations.save(new Organization("Paged " + tag));
		for (int i = 1; i <= 5; i++) {
			branch(org, "Shop " + i + " " + tag);
		}
		String token = tokenFor(newUser(org, "mgr-p-" + tag, RoleType.MANAGER));

		JsonNode first = json(get("/api/v1/branches?page=0&size=2", token).body());
		assertThat(first.get("content").size()).isEqualTo(2);
		assertThat(first.get("totalElements").asLong()).isEqualTo(5);
		assertThat(first.get("totalPages").asInt()).isEqualTo(3);

		JsonNode middle = json(get("/api/v1/branches?page=1&size=2", token).body());
		assertThat(middle.get("content").size()).isEqualTo(2);
		assertThat(middle.get("page").asInt()).isEqualTo(1);

		JsonNode last = json(get("/api/v1/branches?page=2&size=2", token).body());
		assertThat(last.get("content").size()).isEqualTo(1);

		// Beyond total: successful empty page, never 404.
		HttpResponse<String> beyond = get("/api/v1/branches?page=9&size=2", token);
		assertThat(beyond.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode beyondPage = json(beyond.body());
		assertThat(beyondPage.get("content").size()).isEqualTo(0);
		assertThat(beyondPage.get("totalElements").asLong()).isEqualTo(5);
	}

	@Test
	void sortingByNameBothDirections() throws Exception {
		Organization org = organizations.save(new Organization("Sorted " + tag));
		branch(org, "Zulu " + tag);
		branch(org, "Mike " + tag);
		branch(org, "Alfa " + tag);
		String token = tokenFor(newUser(org, "mgr-s-" + tag, RoleType.MANAGER));

		List<String> asc = contentNames(json(
				get("/api/v1/branches?sort=name,asc&size=100", token).body()));
		assertThat(asc).containsExactly("Alfa " + tag, "Mike " + tag, "Zulu " + tag);

		List<String> desc = contentNames(json(
				get("/api/v1/branches?sort=name,desc&size=100", token).body()));
		assertThat(desc).containsExactly("Zulu " + tag, "Mike " + tag, "Alfa " + tag);
	}

	@Test
	void defaultSortIsNameAscending() throws Exception {
		Organization org = organizations.save(new Organization("DefSort " + tag));
		branch(org, "Zulu " + tag);
		branch(org, "Alfa " + tag);
		String token = tokenFor(newUser(org, "mgr-d-" + tag, RoleType.MANAGER));

		List<String> names = contentNames(json(
				get("/api/v1/branches?size=100", token).body()));
		assertThat(names).containsExactly("Alfa " + tag, "Zulu " + tag);
	}

	@Test
	void invalidSortIs400() throws Exception {
		HttpResponse<String> response =
				get("/api/v1/branches?sort=someInternalJpaProperty,asc", managerAToken);
		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(json(response.body()).get("code").asString()).isEqualTo("INVALID_SORT");
	}

	@Test
	void invalidSortDirectionIs400() throws Exception {
		HttpResponse<String> response =
				get("/api/v1/branches?sort=name,sideways", managerAToken);
		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(json(response.body()).get("code").asString()).isEqualTo("INVALID_SORT");
	}

	@Test
	void pageSizeLimitsAreEnforced() throws Exception {
		assertThat(json(get("/api/v1/branches?size=101", managerAToken).body())
				.get("code").asString()).isEqualTo("INVALID_SIZE");
		assertThat(get("/api/v1/branches?size=101", managerAToken).statusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(json(get("/api/v1/branches?size=0", managerAToken).body())
				.get("code").asString()).isEqualTo("INVALID_SIZE");
		assertThat(json(get("/api/v1/branches?page=-1", managerAToken).body())
				.get("code").asString()).isEqualTo("INVALID_PAGE");
	}

	@Test
	void statusFilterNarrowsResults() throws Exception {
		Organization org = organizations.save(new Organization("Filtered " + tag));
		Branch active = branch(org, "Open " + tag);
		Branch retired = branch(org, "Closed " + tag);
		String token = tokenFor(newUser(org, "mgr-f-" + tag, RoleType.MANAGER));
		post("/api/v1/branches/" + retired.getId() + "/deactivate", null, token);

		List<String> inactive = contentNames(json(
				get("/api/v1/branches?status=INACTIVE&size=100", token).body()));
		assertThat(inactive).containsExactly("Closed " + tag);

		List<String> activeNames = contentNames(json(
				get("/api/v1/branches?status=ACTIVE&size=100", token).body()));
		assertThat(activeNames).contains("Open " + tag);
		assertThat(activeNames).doesNotContain("Closed " + tag);
		assertThat(active.getId()).isNotNull();

		HttpResponse<String> bad = get("/api/v1/branches?status=BOGUS", token);
		assertThat(bad.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
	}

	// -- Get: roles ------------------------------------------------------------

	@Test
	void adminReadsOwnBranch() throws Exception {
		HttpResponse<String> response =
				get("/api/v1/branches/" + branchAlpha.getId(), adminAToken);
		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(response.body()).get("name").asString())
				.isEqualTo("Alpha " + tag);
	}

	@Test
	void crossTenantReadsDenyBothDirections() throws Exception {
		assertThat(get("/api/v1/branches/" + branchDelta.getId(), managerAToken)
				.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/branches/" + branchAlpha.getId(), managerBToken)
				.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	// -- Create ------------------------------------------------------------------

	@Test
	void managerCreatesBranchWithLocation() throws Exception {
		HttpResponse<String> response = post("/api/v1/branches",
				"{\"name\":\"Riverside " + tag + "\",\"addressLine1\":\"12 Main St\","
						+ "\"city\":\"Indore\",\"state\":\"MP\",\"postalCode\":\"452001\","
						+ "\"country\":\"India\",\"timeZone\":\"Asia/Kolkata\"}",
				managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
		JsonNode body = json(response.body());
		assertThat(body.get("name").asString()).isEqualTo("Riverside " + tag);
		assertThat(body.get("organizationId").asString()).isEqualTo(orgA.getId().toString());
		assertThat(body.get("addressLine1").asString()).isEqualTo("12 Main St");
		assertThat(body.get("state").asString()).isEqualTo("MP");
		assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
		String location = response.headers().firstValue("Location").orElseThrow();
		assertThat(location).endsWith("/api/v1/branches/" + body.get("id").asString());
		assertThat(response.body()).doesNotContain("passwordHash");
	}

	@Test
	void adminCreatesBranch() throws Exception {
		assertThat(post("/api/v1/branches", branchPayload("Uptown " + tag), adminAToken)
				.statusCode()).isEqualTo(HttpStatus.CREATED.value());
	}

	@Test
	void staffAndCustomerCannotCreate() throws Exception {
		assertThat(post("/api/v1/branches", branchPayload("Staff " + tag), staffAToken)
				.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post("/api/v1/branches", branchPayload("Cust " + tag), customerAToken)
				.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post("/api/v1/branches", branchPayload("Anon " + tag), null)
				.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	@Test
	void createSuppliedOrganizationIdIsIgnored() throws Exception {
		HttpResponse<String> response = post("/api/v1/branches",
				"{\"name\":\"Own " + tag + "\",\"city\":\"Indore\",\"country\":\"India\","
						+ "\"timeZone\":\"Asia/Kolkata\",\"organizationId\":\""
						+ orgB.getId() + "\"}",
				managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
		assertThat(json(response.body()).get("organizationId").asString())
				.isEqualTo(orgA.getId().toString());
	}

	@Test
	void createValidationFailuresAre400() throws Exception {
		HttpResponse<String> blankName = post("/api/v1/branches",
				"{\"name\":\"\",\"city\":\"Indore\",\"country\":\"India\","
						+ "\"timeZone\":\"Asia/Kolkata\"}",
				managerAToken);
		assertThat(blankName.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(json(blankName.body()).get("code").asString())
				.isEqualTo("VALIDATION_ERROR");

		HttpResponse<String> badZone = post("/api/v1/branches",
				"{\"name\":\"Nowhere " + tag + "\",\"city\":\"Indore\",\"country\":\"India\","
						+ "\"timeZone\":\"Not/AZone\"}",
				managerAToken);
		assertThat(badZone.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(json(badZone.body()).get("code").asString())
				.isEqualTo("INVALID_BRANCH_REQUEST");
	}

	@Test
	void duplicateNameConflictsWithinOrganizationOnly() throws Exception {
		HttpResponse<String> duplicate = post("/api/v1/branches",
				branchPayload("Alpha " + tag), managerAToken);
		assertThat(duplicate.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		JsonNode body = json(duplicate.body());
		assertThat(body.get("code").asString()).isEqualTo("BRANCH_NAME_CONFLICT");
		assertThat(duplicate.body()).doesNotContain("uq_branch_organization_name");
		assertThat(duplicate.body()).doesNotContain("PSQLException");

		// Same name in the other organization stays legal.
		assertThat(post("/api/v1/branches", branchPayload("Alpha " + tag), managerBToken)
				.statusCode()).isEqualTo(HttpStatus.CREATED.value());
	}

	// -- Update --------------------------------------------------------------------

	@Test
	void managerUpdatesBranch() throws Exception {
		Branch target = branch(orgA, "Renovate " + tag);
		HttpResponse<String> response = put("/api/v1/branches/" + target.getId(),
				"{\"name\":\"Renovated " + tag + "\",\"addressLine1\":\"9 New Rd\","
						+ "\"city\":\"Bhopal\",\"country\":\"India\",\"timeZone\":\"Asia/Kolkata\"}",
				managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(body.get("name").asString()).isEqualTo("Renovated " + tag);
		assertThat(body.get("city").asString()).isEqualTo("Bhopal");
		assertThat(body.get("organizationId").asString()).isEqualTo(orgA.getId().toString());
	}

	@Test
	void adminUpdatesBranch() throws Exception {
		Branch target = branch(orgA, "AdminEdit " + tag);
		assertThat(put("/api/v1/branches/" + target.getId(),
				branchPayload("AdminEdited " + tag), adminAToken).statusCode())
				.isEqualTo(HttpStatus.OK.value());
	}

	@Test
	void staffAndCustomerCannotUpdate() throws Exception {
		Branch target = branch(orgA, "Guarded " + tag);
		assertThat(put("/api/v1/branches/" + target.getId(),
				branchPayload("GuardedX " + tag), staffAToken).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(put("/api/v1/branches/" + target.getId(),
				branchPayload("GuardedY " + tag), customerAToken).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	@Test
	void updateForeignOrUnknownIs404() throws Exception {
		Branch target = branch(orgA, "Local " + tag);
		assertThat(put("/api/v1/branches/" + branchDelta.getId(),
				branchPayload("Remote " + tag), managerAToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(put("/api/v1/branches/" + target.getId(),
				branchPayload("Remote " + tag), managerBToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(put("/api/v1/branches/" + UUID.randomUUID(),
				branchPayload("Ghost " + tag), managerAToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void updateValidationAndDuplicate() throws Exception {
		Branch target = branch(orgA, "Strict " + tag);
		HttpResponse<String> blank = put("/api/v1/branches/" + target.getId(),
				"{\"name\":\"\",\"city\":\"Indore\",\"country\":\"India\","
						+ "\"timeZone\":\"Asia/Kolkata\"}",
				managerAToken);
		assertThat(blank.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());

		HttpResponse<String> clash = put("/api/v1/branches/" + target.getId(),
				branchPayload("Alpha " + tag), managerAToken);
		assertThat(clash.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(json(clash.body()).get("code").asString())
				.isEqualTo("BRANCH_NAME_CONFLICT");
	}

	@Test
	void updateCannotChangeOwnershipOrStatus() throws Exception {
		Branch target = branch(orgA, "Pinned " + tag);
		HttpResponse<String> response = put("/api/v1/branches/" + target.getId(),
				"{\"name\":\"Pinned " + tag + "\",\"city\":\"Indore\",\"country\":\"India\","
						+ "\"timeZone\":\"Asia/Kolkata\",\"organizationId\":\""
						+ orgB.getId() + "\",\"status\":\"INACTIVE\"}",
				managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(body.get("organizationId").asString()).isEqualTo(orgA.getId().toString());
		assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
	}

	// -- Activate / deactivate ---------------------------------------------------------

	@Test
	void deactivateAndActivateCycle() throws Exception {
		Branch target = branch(orgA, "Cycling " + tag);

		HttpResponse<String> off =
				post("/api/v1/branches/" + target.getId() + "/deactivate", null, managerAToken);
		assertThat(off.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(off.body()).get("status").asString()).isEqualTo("INACTIVE");

		// Deactivated branches stay visible to reads.
		assertThat(json(get("/api/v1/branches/" + target.getId(), managerAToken).body())
				.get("status").asString()).isEqualTo("INACTIVE");

		// Idempotent second call.
		assertThat(post("/api/v1/branches/" + target.getId() + "/deactivate", null,
				managerAToken).statusCode()).isEqualTo(HttpStatus.OK.value());

		HttpResponse<String> on =
				post("/api/v1/branches/" + target.getId() + "/activate", null, adminAToken);
		assertThat(on.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(on.body()).get("status").asString()).isEqualTo("ACTIVE");
	}

	@Test
	void statusCommandsRespectScopeAndRole() throws Exception {
		Branch target = branch(orgA, "Scoped " + tag);
		String off = "/api/v1/branches/" + target.getId() + "/deactivate";

		assertThat(post(off, null, staffAToken).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post(off, null, customerAToken).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post(off, null, null).statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());
		assertThat(post("/api/v1/branches/" + branchDelta.getId() + "/deactivate",
				null, managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(post(off, null, managerBToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
	}
}

