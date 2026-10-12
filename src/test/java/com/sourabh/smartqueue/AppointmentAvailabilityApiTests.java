package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import com.sourabh.smartqueue.holiday.Holiday;
import com.sourabh.smartqueue.holiday.HolidayRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.staff.StaffAssignmentRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.workinghour.WorkingHour;
import com.sourabh.smartqueue.workinghour.WorkingHourRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Appointment availability API proofs over real HTTP (see ADR-039).
 *
 * <p>Verifies the advisory slot endpoint end-to-end: response shape,
 * branch-local date semantics, holiday veto, working-interval fit,
 * past/today exclusion, service/customer conflicts, tenant isolation,
 * inactive resources, and 422 branch/service mismatch. Non-transactional;
 * slots are unique per test via a counter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AppointmentAvailabilityApiTests {

	static final String PASSWORD = "Avail-Api-19";
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
	UserRepository users;

	@Autowired
	OrganizationMembershipRepository memberships;

	@Autowired
	WorkingHourRepository workingHours;

	@Autowired
	HolidayRepository holidays;

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
	User customerA1;
	User customerB1;

	String customerA1Token;
	String customerB1Token;
	String managerAToken;
	String managerBToken;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		tag = UUID.randomUUID().toString().substring(0, 8);
		orgA = organizations.save(new Organization("Avail Org A " + tag));
		branchA1 = branches.save(new Branch(orgA, "Downtown " + tag,
				"Indore", "India", KOLKATA));
		branchA2 = branches.save(new Branch(orgA, "Uptown " + tag,
				"Bhopal", "India", KOLKATA));
		orgB = organizations.save(new Organization("Avail Org B " + tag));
		branchB1 = branches.save(new Branch(orgB, "Riverside " + tag,
				"Pune", "India", KOLKATA));

		// Full-week coverage, 09:00–18:00 Kolkata.
		for (DayOfWeek day : DayOfWeek.values()) {
			workingHours.save(new WorkingHour(branchA1, day,
					LocalTime.of(9, 0), LocalTime.of(18, 0)));
			workingHours.save(new WorkingHour(branchA2, day,
					LocalTime.of(9, 0), LocalTime.of(18, 0)));
			workingHours.save(new WorkingHour(branchB1, day,
					LocalTime.of(9, 0), LocalTime.of(18, 0)));
		}

		svcA1 = service(branchA1, "Consult " + tag, 30);
		svcA2 = service(branchA2, "Xray " + tag, 15);
		svcB1 = service(branchB1, "Consult " + tag, 30);

		customerA1 = newUser(orgA, "cust-a1-" + tag, RoleType.CUSTOMER);
		customerB1 = newUser(orgB, "cust-b1-" + tag, RoleType.CUSTOMER);
		User managerA = newUser(orgA, "mgr-a-" + tag, RoleType.MANAGER);
		User managerB = newUser(orgB, "mgr-b-" + tag, RoleType.MANAGER);

		customerA1Token = tokenFor(customerA1);
		customerB1Token = tokenFor(customerB1);
		managerAToken = tokenFor(managerA);
		managerBToken = tokenFor(managerB);
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private Service service(Branch branch, String name, int minutes) {
		return services.save(new Service(branch, name, Duration.ofMinutes(minutes)));
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

	private JsonNode json(String body) {
		try {
			return objectMapper.readTree(body);
		} catch (tools.jackson.core.JacksonException e) {
			throw new IllegalStateException("unreadable JSON response: " + body, e);
		}
	}

	/** Future date that always falls on a covered weekday. */
	private LocalDate futureDate() {
		return LocalDate.now(KOLKATA).plusDays(7);
	}

	private String availabilityPath(UUID branchId, UUID serviceId, LocalDate date) {
		return "/api/v1/branches/" + branchId + "/appointments/availability"
				+ "?serviceId=" + serviceId + "&date=" + date;
	}

	// -- Contract shape -------------------------------------------------------

	@Test
	void returnsExpectedResponseShape() throws Exception {
		LocalDate date = futureDate();
		HttpResponse<String> response = get(
				availabilityPath(branchA1.getId(), svcA1.getId(), date), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(body.get("branchId").asText()).isEqualTo(branchA1.getId().toString());
		assertThat(body.get("serviceId").asText()).isEqualTo(svcA1.getId().toString());
		assertThat(body.get("date").asText()).isEqualTo(date.toString());
		assertThat(body.get("timezone").asText()).isEqualTo("Asia/Kolkata");
		assertThat(body.get("serviceDurationMinutes").asInt()).isEqualTo(30);
		assertThat(body.get("holiday").isNull()).isTrue();
		assertThat(body.get("workingIntervals").isArray()).isTrue();
		assertThat(body.get("workingIntervals")).hasSize(1);
		assertThat(body.get("workingIntervals").get(0).get("opensAt").asText()).isEqualTo("09:00:00");
		assertThat(body.get("workingIntervals").get(0).get("closesAt").asText()).isEqualTo("18:00:00");
		assertThat(body.get("slots").isArray()).isTrue();
		assertThat(body.get("slots").size()).isGreaterThan(0);
		// Each slot has start + end.
		assertThat(body.get("slots").get(0).has("start")).isTrue();
		assertThat(body.get("slots").get(0).has("end")).isTrue();
	}

	// -- Slot correctness -----------------------------------------------------

	@Test
	void slotsAreChronologicalAndFitDuration() throws Exception {
		LocalDate date = futureDate();
		HttpResponse<String> response = get(
				availabilityPath(branchA1.getId(), svcA1.getId(), date), customerA1Token);

		JsonNode slots = json(response.body()).get("slots");
		assertThat(slots.size()).isGreaterThan(1);
		for (int i = 1; i < slots.size(); i++) {
			assertThat(slots.get(i).get("start").asText())
					.isGreaterThan(slots.get(i - 1).get("start").asText());
		}
		// First slot starts at 09:00 Kolkata.
		String firstStart = slots.get(0).get("start").asText();
		ZonedDateTime expected = date.atTime(LocalTime.of(9, 0)).atZone(KOLKATA);
		assertThat(firstStart).isEqualTo(expected.toInstant().toString());
	}

	@Test
	void holidayDateReturnsZeroSlotsWithHolidayEcho() throws Exception {
		LocalDate date = futureDate();
		holidays.save(new Holiday(branchA1, date, "Diwali"));

		HttpResponse<String> response = get(
				availabilityPath(branchA1.getId(), svcA1.getId(), date), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(body.get("holiday").get("name").asText()).isEqualTo("Diwali");
		assertThat(body.get("holiday").get("date").asText()).isEqualTo(date.toString());
		assertThat(body.get("slots").size()).isEqualTo(0);
	}

	@Test
	void pastDateReturnsZeroSlots() throws Exception {
		HttpResponse<String> response = get(
				availabilityPath(branchA1.getId(), svcA1.getId(), LocalDate.of(2020, 1, 1)),
				customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(response.body()).get("slots").size()).isEqualTo(0);
	}

	@Test
	void serviceConflictRemovesOverlappingSlots() throws Exception {
		LocalDate date = futureDate();
		// Book 10:00–10:30 on the target date.
		String start = date.atTime(LocalTime.of(10, 0)).atZone(KOLKATA).toInstant().toString();
		HttpResponse<String> booked = post("/api/v1/branches/" + branchA1.getId() + "/appointments",
				"{\"serviceId\":\"" + svcA1.getId() + "\",\"startAt\":\"" + start + "\"}",
				customerA1Token);
		assertThat(booked.statusCode()).isEqualTo(HttpStatus.CREATED.value());

		HttpResponse<String> response = get(
				availabilityPath(branchA1.getId(), svcA1.getId(), date), customerA1Token);

		JsonNode slots = json(response.body()).get("slots");
		// No slot should overlap 10:00–10:30.
		for (int i = 0; i < slots.size(); i++) {
			String slotStart = slots.get(i).get("start").asText();
			String slotEnd = slots.get(i).get("end").asText();
			assertThat(slotStart.compareTo(date.atTime(LocalTime.of(10, 30))
					.atZone(KOLKATA).toInstant().toString()) < 0
					&& slotEnd.compareTo(date.atTime(LocalTime.of(10, 0))
					.atZone(KOLKATA).toInstant().toString()) > 0).isFalse();
		}
	}

	@Test
	void customerConflictOnDifferentServiceRemovesSlots() throws Exception {
		LocalDate date = futureDate();
		Service other = service(branchA1, "Dental " + tag, 30);
		String start = date.atTime(LocalTime.of(10, 0)).atZone(KOLKATA).toInstant().toString();
		HttpResponse<String> booked = post("/api/v1/branches/" + branchA1.getId() + "/appointments",
				"{\"serviceId\":\"" + other.getId() + "\",\"startAt\":\"" + start + "\"}",
				customerA1Token);
		assertThat(booked.statusCode()).isEqualTo(HttpStatus.CREATED.value());

		HttpResponse<String> response = get(
				availabilityPath(branchA1.getId(), svcA1.getId(), date), customerA1Token);

		JsonNode slots = json(response.body()).get("slots");
		for (int i = 0; i < slots.size(); i++) {
			String slotStart = slots.get(i).get("start").asText();
			String slotEnd = slots.get(i).get("end").asText();
			assertThat(slotStart.compareTo(date.atTime(LocalTime.of(10, 30))
					.atZone(KOLKATA).toInstant().toString()) < 0
					&& slotEnd.compareTo(date.atTime(LocalTime.of(10, 0))
					.atZone(KOLKATA).toInstant().toString()) > 0).isFalse();
		}
	}

	// -- Authorization / tenant isolation --------------------------------------

	@Test
	void customerFromAnotherOrganizationReadsAvailability() throws Exception {
		// ADR-040: a platform-level customer belongs to no organization —
		// availability at any ACTIVE branch is readable.
		LocalDate date = futureDate();
		HttpResponse<String> response = get(
				availabilityPath(branchA1.getId(), svcA1.getId(), date), customerB1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(response.body()).get("branchId").asString())
				.isEqualTo(branchA1.getId().toString());
	}

	@Test
	void serviceFromAnotherBranchIs422() throws Exception {
		LocalDate date = futureDate();
		HttpResponse<String> response = get(
				availabilityPath(branchA1.getId(), svcA2.getId(), date), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
		assertThat(json(response.body()).get("code").asText()).isEqualTo("APPOINTMENT_BRANCH_MISMATCH");
	}

	@Test
	void inactiveBranchIs400() throws Exception {
		LocalDate date = futureDate();
		Branch inactive = branches.save(new Branch(orgA, "Closed " + tag, "Indore", "India", KOLKATA));
		inactive.setStatus(com.sourabh.smartqueue.branch.BranchStatus.INACTIVE);
		branches.save(inactive);
		Service svc = service(inactive, "Consult " + tag, 30);
		for (DayOfWeek day : DayOfWeek.values()) {
			workingHours.save(new WorkingHour(inactive, day,
					LocalTime.of(9, 0), LocalTime.of(18, 0)));
		}

		HttpResponse<String> response = get(
				availabilityPath(inactive.getId(), svc.getId(), date), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(json(response.body()).get("code").asText()).isEqualTo("INVALID_APPOINTMENT_REQUEST");
	}

	@Test
	void inactiveServiceIs400() throws Exception {
		LocalDate date = futureDate();
		Service inactive = service(branchA1, "Retired " + tag, 30);
		inactive.setStatus(com.sourabh.smartqueue.service.ServiceStatus.INACTIVE);
		services.save(inactive);

		HttpResponse<String> response = get(
				availabilityPath(branchA1.getId(), inactive.getId(), date), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(json(response.body()).get("code").asText()).isEqualTo("INVALID_APPOINTMENT_REQUEST");
	}

	@Test
	void unauthenticatedRequestIs401() throws Exception {
		HttpResponse<String> response = get(
				availabilityPath(branchA1.getId(), svcA1.getId(), futureDate()), null);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	// -- Closed day -------------------------------------------------------------

	@Test
	void closedDayReturnsEmptySlotsAndEmptyIntervals() throws Exception {
		// Remove all working hours for one day.
		LocalDate closed = futureDate();
		DayOfWeek dow = closed.getDayOfWeek();
		for (WorkingHour wh : workingHours.findByBranchIdAndDayOfWeek(branchA1.getId(), dow)) {
			workingHours.delete(wh);
		}

		HttpResponse<String> response = get(
				availabilityPath(branchA1.getId(), svcA1.getId(), closed), customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(body.get("slots").size()).isEqualTo(0);
		assertThat(body.get("workingIntervals")).isEmpty();
		assertThat(body.get("holiday").isNull()).isTrue();
	}
}

