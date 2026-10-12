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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.sourabh.smartqueue.appointment.Appointment;
import com.sourabh.smartqueue.appointment.AppointmentRepository;
import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Analytics HTTP contract (see ADR-035): the overview endpoint over
 * real HTTP — management-only access (backend-enforced 403s),
 * scope-safe 404s for foreign ids, the 400/422 request contract, and
 * the response shape with zero-data semantics.
 *
 * <p>NOT transactional: the HTTP server thread cannot see uncommitted
 * test transactions, so fixtures commit for real (the documented
 * black-box lesson). Metric math itself is proven rollback-safe in
 * {@link AnalyticsServiceTests}; here the assertions stay at the
 * contract level (status codes, envelope codes, shape, isolation).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalyticsApiTests {

	private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	private static final String PASSWORD = "correct-horse-9";
	private static final String WINDOW =
			"from=2026-10-07T00:00:00&to=2026-10-08T00:00:00&timezone=UTC";

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@LocalServerPort
	int port;

	@Autowired
	OrganizationRepository organizations;
	@Autowired
	BranchRepository branches;
	@Autowired
	ServiceRepository services;
	@Autowired
	UserRepository users;

	@Autowired
	OrganizationMembershipRepository memberships;
	@Autowired
	AppointmentRepository appointments;
	@Autowired
	AuthService authService;
	@Autowired
	PasswordCredentialService credentials;
	@Autowired
	ObjectMapper objectMapper;

	private final HttpClient http = HttpClient.newHttpClient();

	String tag;
	Organization orgA;
	Branch branchA;
	com.sourabh.smartqueue.service.Service serviceA;
	User managerA;
	User adminA;
	User staffA;
	User customerA;
	String managerToken;
	String adminToken;
	String staffToken;
	String customerToken;
	Organization orgB;
	Branch branchB;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		tag = UUID.randomUUID().toString().substring(0, 8);
		orgA = organizations.save(new Organization("Analytics Org A " + tag));
		branchA = branches.save(new Branch(orgA, "Downtown " + tag, "Bhopal", "India", KOLKATA));
		serviceA = services.save(new com.sourabh.smartqueue.service.Service(
				branchA, "Consultation", Duration.ofMinutes(30)));
		managerA = newUser(orgA, "mgr-" + tag, RoleType.MANAGER);
		adminA = newUser(orgA, "adm-" + tag, RoleType.ADMIN);
		staffA = newUser(orgA, "staff-" + tag, RoleType.STAFF);
		customerA = newUser(orgA, "cust-" + tag, RoleType.CUSTOMER);
		managerToken = tokenFor(managerA);
		adminToken = tokenFor(adminA);
		staffToken = tokenFor(staffA);
		customerToken = tokenFor(customerA);
		orgB = organizations.save(new Organization("Analytics Org B " + tag));
		branchB = branches.save(new Branch(orgB, "Elsewhere " + tag, "Pune", "India", KOLKATA));
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
				.uri(URI.create("http://localhost:" + port + path)).GET();
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void unauthenticatedIs401() throws Exception {
		assertThat(get("/api/v1/analytics/overview?" + WINDOW, null).statusCode())
				.isEqualTo(401);
	}

	@Test
	void staffAndCustomerAreForbidden() throws Exception {
		assertThat(get("/api/v1/analytics/overview?" + WINDOW, staffToken).statusCode())
				.isEqualTo(403);
		HttpResponse<String> customer = get("/api/v1/analytics/overview?" + WINDOW, customerToken);
		assertThat(customer.statusCode()).isEqualTo(403);
		assertThat(objectMapper.readTree(customer.body()).get("code").asText())
				.isEqualTo("FORBIDDEN");
	}

	@Test
	void managerAndAdminReceiveTheReportShape() throws Exception {
		Instant start = Instant.parse("2026-10-07T10:00:00Z");
		appointments.save(new Appointment(orgA, branchA, serviceA, customerA,
				start, start.plus(Duration.ofMinutes(30))));

		HttpResponse<String> response = get("/api/v1/analytics/overview?" + WINDOW
				+ "&branchId=" + branchA.getId(), managerToken);

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = objectMapper.readTree(response.body());
		assertThat(body.get("from").asText()).isEqualTo("2026-10-07T00:00:00Z");
		assertThat(body.get("to").asText()).isEqualTo("2026-10-08T00:00:00Z");
		assertThat(body.get("timezone").asText()).isEqualTo("UTC");
		assertThat(body.get("branchId").asText()).isEqualTo(branchA.getId().toString());
		assertThat(body.get("appointments").get("total").asLong()).isEqualTo(1);
		assertThat(body.get("appointments").get("scheduled").asLong()).isEqualTo(1);
		assertThat(body.get("queue").get("joined").asLong()).isEqualTo(0);
		assertThat(body.get("queue").get("averageWaitMinutes").isNull()).isTrue();
		assertThat(body.get("sessions").get("total").asLong()).isEqualTo(0);
		assertThat(body.get("serviceBreakdown").size()).isEqualTo(1);
		assertThat(body.get("branchBreakdown").size()).isEqualTo(1);
		// No organization identity, recipient data, or credentials leak.
		assertThat(body.has("organizationId")).isFalse();

		HttpResponse<String> admin = get("/api/v1/analytics/overview?" + WINDOW, adminToken);
		assertThat(admin.statusCode()).isEqualTo(200);
		assertThat(objectMapper.readTree(admin.body()).get("timezone").asText())
				.isEqualTo("UTC");
	}

	@Test
	void foreignBranchIsScopeSafe404() throws Exception {
		HttpResponse<String> response = get("/api/v1/analytics/overview?" + WINDOW
				+ "&branchId=" + branchB.getId(), managerToken);

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(objectMapper.readTree(response.body()).get("code").asText())
				.isEqualTo("BRANCH_NOT_FOUND");
	}

	@Test
	void organizationWideReportRequiresTimezone() throws Exception {
		HttpResponse<String> response = get("/api/v1/analytics/overview"
				+ "?from=2026-10-07T00:00:00&to=2026-10-08T00:00:00", managerToken);

		assertThat(response.statusCode()).isEqualTo(400);
	}

	@Test
	void invalidRangeAndTimezoneAre400() throws Exception {
		assertThat(get("/api/v1/analytics/overview"
				+ "?from=2026-10-08T00:00:00&to=2026-10-07T00:00:00&timezone=UTC",
				managerToken).statusCode()).isEqualTo(400);
		assertThat(get("/api/v1/analytics/overview"
				+ "?from=2020-01-01T00:00:00&to=2026-10-08T00:00:00&timezone=UTC",
				managerToken).statusCode()).isEqualTo(400);
		assertThat(get("/api/v1/analytics/overview?" + WINDOW.replace("timezone=UTC",
				"timezone=Not/AZone"), managerToken).statusCode()).isEqualTo(400);
		assertThat(get("/api/v1/analytics/overview", managerToken).statusCode())
				.isEqualTo(400);
	}

	@Test
	void serviceOutsideBranchIs422() throws Exception {
		Branch branchA2 = branches.save(
				new Branch(orgA, "Second " + tag, "Bhopal", "India", KOLKATA));

		HttpResponse<String> response = get("/api/v1/analytics/overview?" + WINDOW
				+ "&branchId=" + branchA2.getId() + "&serviceId=" + serviceA.getId(),
				managerToken);

		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(objectMapper.readTree(response.body()).get("code").asText())
				.isEqualTo("ANALYTICS_SCOPE_MISMATCH");
	}

	@Test
	void emptyWindowReturnsZeroDataContract() throws Exception {
		HttpResponse<String> response = get("/api/v1/analytics/overview"
				+ "?from=2030-01-01T00:00:00&to=2030-01-08T00:00:00&timezone=UTC",
				managerToken);

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = objectMapper.readTree(response.body());
		assertThat(body.get("appointments").get("total").asLong()).isEqualTo(0);
		assertThat(body.get("queue").get("joined").asLong()).isEqualTo(0);
		assertThat(body.get("queue").get("averageWaitMinutes").isNull()).isTrue();
		assertThat(body.get("sessions").get("averageDurationMinutes").isNull()).isTrue();
		assertThat(body.get("serviceBreakdown").size()).isEqualTo(0);
		assertThat(body.get("branchBreakdown").size()).isEqualTo(0);
	}
}

