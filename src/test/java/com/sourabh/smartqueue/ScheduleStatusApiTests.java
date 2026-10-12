package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
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
import com.sourabh.smartqueue.branch.BranchStatus;
import com.sourabh.smartqueue.holiday.Holiday;
import com.sourabh.smartqueue.holiday.HolidayRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.workinghour.WorkingHour;
import com.sourabh.smartqueue.workinghour.WorkingHourRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ScheduleStatusApiTests {

	static final String PASSWORD = "Sched-Api-21";
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
	WorkingHourRepository workingHours;

	@Autowired
	HolidayRepository holidays;

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
	Branch branchB1;
	User customerA1;
	User customerB1;
	User managerA;
	String customerA1Token;
	String customerB1Token;
	String managerAToken;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		tag = UUID.randomUUID().toString().substring(0, 8);
		orgA = organizations.save(new Organization("Sched Api Org A " + tag));
		orgB = organizations.save(new Organization("Sched Api Org B " + tag));
		branchA1 = branches.save(new Branch(orgA, "Downtown " + tag, "Indore", "India", KOLKATA));
		branchB1 = branches.save(new Branch(orgB, "Uptown " + tag, "Bhopal", "India", KOLKATA));

		for (DayOfWeek day : DayOfWeek.values()) {
			workingHours.save(new WorkingHour(branchA1, day,
					LocalTime.of(9, 0), LocalTime.of(17, 0)));
		}

		customerA1 = newUser(orgA, "cust-a1-" + tag, RoleType.CUSTOMER);
		customerB1 = newUser(orgB, "cust-b1-" + tag, RoleType.CUSTOMER);
		managerA = newUser(orgA, "mgr-a-" + tag, RoleType.MANAGER);

		customerA1Token = tokenFor(customerA1);
		customerB1Token = tokenFor(customerB1);
		managerAToken = tokenFor(managerA);
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

	private String schedulePath(UUID branchId) {
		return "/api/v1/branches/" + branchId + "/schedule-status";
	}

	@Test
	void successReturnsExpectedShape() throws Exception {
		HttpResponse<String> response = get(schedulePath(branchA1.getId()), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(body.get("branchId").asText()).isEqualTo(branchA1.getId().toString());
		assertThat(body.get("timezone").asText()).isEqualTo("Asia/Kolkata");
		assertThat(body.get("now")).isNotNull();
		assertThat(body.has("state")).isTrue();
		assertThat(body.get("todayIntervals").isArray()).isTrue();
		assertThat(body.get("todayIntervals")).hasSize(1);
		assertThat(body.get("todayIntervals").get(0).get("opensAt").asText()).isEqualTo("09:00:00");
		assertThat(body.get("todayIntervals").get(0).get("closesAt").asText()).isEqualTo("17:00:00");
		assertThat(body.has("upcomingHoliday")).isTrue();
		assertThat(body.has("reason")).isTrue();
	}

	@Test
	void inactiveBranchReturnsInactiveState() throws Exception {
		branchA1.setStatus(BranchStatus.INACTIVE);
		branches.save(branchA1);

		HttpResponse<String> response = get(schedulePath(branchA1.getId()), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(response.body()).get("state").asString()).isEqualTo("INACTIVE");
	}

	@Test
	void holidayReturnsHolidayState() throws Exception {
		LocalDate today = LocalDate.now(KOLKATA);
		holidays.save(new Holiday(branchA1, today, "Test Holiday"));

		HttpResponse<String> response = get(schedulePath(branchA1.getId()), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(body.get("state").asString()).isEqualTo("HOLIDAY");
		assertThat(body.get("reason").asString()).isEqualTo("Test Holiday");
	}

	@Test
	void closedDayReturnsClosedState() throws Exception {
		for (WorkingHour wh : workingHours.findByBranchId(branchA1.getId())) {
			workingHours.delete(wh);
		}

		HttpResponse<String> response = get(schedulePath(branchA1.getId()), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(body.get("state").asString()).isEqualTo("CLOSED");
		assertThat(body.get("todayIntervals").isArray()).isTrue();
		assertThat(body.get("todayIntervals")).isEmpty();
	}

	@Test
	void customerFromAnotherOrganizationReadsSchedule() throws Exception {
		// ADR-040: a platform-level customer belongs to no organization —
		// schedule status at any ACTIVE branch of an ACTIVE organization
		// is readable.
		HttpResponse<String> response = get(schedulePath(branchA1.getId()), customerB1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(response.body()).get("branchId").asString())
				.isEqualTo(branchA1.getId().toString());
	}

	@Test
	void unknownBranchReturns404() throws Exception {
		HttpResponse<String> response = get(schedulePath(UUID.randomUUID()), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(json(response.body()).get("code").asText()).isEqualTo("BRANCH_NOT_FOUND");
	}

	@Test
	void unauthenticatedRequestReturns401() throws Exception {
		HttpResponse<String> response = get(schedulePath(branchA1.getId()), null);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	@Test
	void managerCanReadScheduleStatus() throws Exception {
		// Deterministic by construction: a holiday on the branch-local
		// today takes precedence over working hours at any wall-clock
		// time, so the manager read asserts an explicit schedule
		// state instead of depending on the real clock falling inside
		// [09:00, 17:00) IST (the old OPEN assertion failed around
		// the clock). Shape mirrors successReturnsExpectedShape.
		LocalDate today = LocalDate.now(KOLKATA);
		holidays.save(new Holiday(branchA1, today, "Manager Read Holiday"));

		HttpResponse<String> response = get(schedulePath(branchA1.getId()), managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(body.get("branchId").asString()).isEqualTo(branchA1.getId().toString());
		assertThat(body.get("state").asString()).isEqualTo("HOLIDAY");
		assertThat(body.get("reason").asString()).isEqualTo("Manager Read Holiday");
	}

	@Test
	void crossOrgManagerCannotReadBranch() throws Exception {
		HttpResponse<String> response = get(schedulePath(branchB1.getId()), managerAToken);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}
}

