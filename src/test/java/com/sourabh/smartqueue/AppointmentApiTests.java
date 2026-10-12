package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.sourabh.smartqueue.service.ServiceStatus;
import com.sourabh.smartqueue.staff.StaffAssignment;
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
 * Appointment API proofs over real HTTP (see ADR-026).
 *
 * <p>Self-service booking for every role, calendar/conflict delegation,
 * privacy-first reads, role-routed listing, lifecycle commands with
 * domain-honest 409s, and both cross-tenant directions — through the real
 * filter chain. Non-transactional; slots are unique per test (a shared
 * counter spaces them two hours apart) so no test contends with another.
 * Working hours cover whole days, so slot arithmetic stays trivial.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AppointmentApiTests {

	static final String PASSWORD = "Appt-Api-15";
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
	private final AtomicInteger slotCounter = new AtomicInteger();

	String tag;
	Organization orgA;
	Branch branchA1;
	Branch branchA2;
	Organization orgB;
	Branch branchB1;
	Service svcA1;
	Service svcA2;
	Service svcB1;
	Service svcInactive;
	User customerA1;
	User customerA2;
	User staffA1;
	User managerA;

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
		orgA = organizations.save(new Organization("Appt Org A " + tag));
		branchA1 = branches.save(new Branch(orgA, "Downtown " + tag,
				"Indore", "India", KOLKATA));
		branchA2 = branches.save(new Branch(orgA, "Uptown " + tag,
				"Bhopal", "India", KOLKATA));
		orgB = organizations.save(new Organization("Appt Org B " + tag));
		branchB1 = branches.save(new Branch(orgB, "Riverside " + tag,
				"Pune", "India", KOLKATA));

		fullWeek(branchA1);
		fullWeek(branchB1);
		// branchA2 deliberately keeps no working hours (outside-hours proof).

		svcA1 = service(branchA1, "Consult " + tag, 30);
		svcA2 = service(branchA2, "Xray " + tag, 15);
		svcB1 = service(branchB1, "Consult " + tag, 30);
		svcInactive = service(branchA1, "Retired " + tag, 30);
		svcInactive.setStatus(ServiceStatus.INACTIVE);
		services.save(svcInactive);

		customerA1 = newUser(orgA, "cust-a1-" + tag, RoleType.CUSTOMER);
		customerA2 = newUser(orgA, "cust-a2-" + tag, RoleType.CUSTOMER);
		staffA1 = newUser(orgA, "staff-a1-" + tag, RoleType.STAFF);
		assignments.save(new StaffAssignment(orgA, staffA1, branchA1));
		managerA = newUser(orgA, "mgr-a-" + tag, RoleType.MANAGER);
		User adminA = newUser(orgA, "adm-a-" + tag, RoleType.ADMIN);
		User managerB = newUser(orgB, "mgr-b-" + tag, RoleType.MANAGER);

		customerA1Token = tokenFor(customerA1);
		customerA2Token = tokenFor(customerA2);
		staffA1Token = tokenFor(staffA1);
		managerAToken = tokenFor(managerA);
		adminAToken = tokenFor(adminA);
		managerBToken = tokenFor(managerB);
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private void fullWeek(Branch branch) {
		for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
			workingHours.save(new WorkingHour(branch, day,
					LocalTime.of(0, 0), LocalTime.of(23, 59)));
		}
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

	/** Unique future 10:00-local slot per call, spaced to never overlap. */
	private Instant slot() {
		int n = slotCounter.getAndIncrement();
		return ZonedDateTime.now(KOLKATA).plusDays(2).withHour(10).withMinute(0)
				.withSecond(0).withNano(0).plusHours(2L * n).toInstant();
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

	private HttpResponse<String> book(UUID branchId, UUID serviceId, Instant start,
			String token) throws Exception {
		return post("/api/v1/branches/" + branchId + "/appointments",
				"{\"serviceId\":\"" + serviceId + "\",\"startAt\":\"" + start + "\"}", token);
	}

	// -- Booking ----------------------------------------------------------------------

	@Test
	void customerBooksOwnAppointment() throws Exception {
		Instant start = slot();
		HttpResponse<String> response = book(branchA1.getId(), svcA1.getId(), start,
				customerA1Token);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
		JsonNode body = json(response.body());
		assertThat(Instant.parse(body.get("startAt").asString())).isEqualTo(start);
		assertThat(Instant.parse(body.get("endAt").asString()))
				.isEqualTo(start.plus(Duration.ofMinutes(30)));
		assertThat(body.get("durationMinutes").asInt()).isEqualTo(30);
		assertThat(body.get("branchId").asString()).isEqualTo(branchA1.getId().toString());
		assertThat(body.get("organizationId").asString())
				.isEqualTo(orgA.getId().toString());
		assertThat(body.get("serviceId").asString()).isEqualTo(svcA1.getId().toString());
		assertThat(body.get("serviceName").asString()).isEqualTo("Consult " + tag);
		assertThat(body.get("customerId").asString())
				.isEqualTo(customerA1.getId().toString());
		assertThat(body.get("branchTimeZone").asString()).isEqualTo("Asia/Kolkata");
		assertThat(body.get("status").asString()).isEqualTo("SCHEDULED");
		String location = response.headers().firstValue("Location").orElseThrow();
		assertThat(location).endsWith("/api/v1/appointments/" + body.get("id").asString());
		assertThat(response.body()).doesNotContain("passwordHash");
	}

	@Test
	void everyRoleBooksForSelfOnly() throws Exception {
		JsonNode asStaff = json(book(branchA1.getId(), svcA1.getId(), slot(),
				staffA1Token).body());
		assertThat(asStaff.get("customerId").asString())
				.isEqualTo(staffA1.getId().toString());

		// No customerId field exists to substitute: the manager's booking
		// names the manager, and anonymous booking is rejected outright.
		JsonNode asManager = json(book(branchA1.getId(), svcA1.getId(), slot(),
				managerAToken).body());
		assertThat(asManager.get("customerId").asString())
				.isEqualTo(managerA.getId().toString());
		assertThat(book(branchA1.getId(), svcA1.getId(), slot(), null).statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	@Test
	void bookingValidation() throws Exception {
		String path = "/api/v1/branches/" + branchA1.getId() + "/appointments";
		assertThat(post(path, "{\"startAt\":\"" + slot() + "\"}", customerA1Token)
				.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(post(path, "{\"serviceId\":\"" + svcA1.getId() + "\"}",
				customerA1Token).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(post(path, "{\"serviceId\":\"not-a-uuid\",\"startAt\":\"" + slot()
				+ "\"}", customerA1Token).statusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(post(path,
				"{\"serviceId\":\"" + svcA1.getId() + "\",\"startAt\":\"yesterday\"}",
				customerA1Token).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
	}

	@Test
	void pastStartIsRejected() throws Exception {
		HttpResponse<String> response = book(branchA1.getId(), svcA1.getId(),
				Instant.now().minus(Duration.ofHours(1)), customerA1Token);
		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
	}

	@Test
	void branchServiceMismatchIs422() throws Exception {
		// Same organization, wrong branch: the combination is invalid.
		HttpResponse<String> mismatch =
				book(branchA1.getId(), svcA2.getId(), slot(), customerA1Token);
		assertThat(mismatch.statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
		assertThat(json(mismatch.body()).get("code").asString())
				.isEqualTo("APPOINTMENT_BRANCH_MISMATCH");

		// ADR-040: a platform-level customer is not bound to one
		// organization — a service at another branch of any ACTIVE
		// organization is the same 422 mismatch as the same-org case
		// (unknown ids stay scope-safe 404s, never the 422).
		assertThat(book(branchA1.getId(), svcB1.getId(), slot(), customerA1Token)
				.statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
		assertThat(book(branchB1.getId(), svcA1.getId(), slot(), customerA1Token)
				.statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
		assertThat(book(UUID.randomUUID(), svcA1.getId(), slot(), customerA1Token)
				.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(book(branchA1.getId(), UUID.randomUUID(), slot(), customerA1Token)
				.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void calendarRulesSurviveThroughRest() throws Exception {
		// Outside working hours: branchA2 keeps no working hours at all.
		HttpResponse<String> outside =
				book(branchA2.getId(), svcA2.getId(), slot(), customerA1Token);
		assertThat(outside.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(json(outside.body()).get("code").asString())
				.isEqualTo("OUTSIDE_WORKING_HOURS");

		// Holiday: a dedicated date keeps other tests' slots untouched.
		Instant holidaySlot = ZonedDateTime.now(KOLKATA).plusDays(9).withHour(10)
				.withMinute(0).withSecond(0).withNano(0).toInstant();
		holidays.save(new Holiday(branchA1,
				holidaySlot.atZone(KOLKATA).toLocalDate(), "Test Day"));
		HttpResponse<String> closed =
				book(branchA1.getId(), svcA1.getId(), holidaySlot, customerA1Token);
		assertThat(closed.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(json(closed.body()).get("code").asString()).isEqualTo("BRANCH_CLOSED");

		// Inactive service: invalid request, not a conflict.
		HttpResponse<String> retired =
				book(branchA1.getId(), svcInactive.getId(), slot(), customerA1Token);
		assertThat(retired.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
	}

	@Test
	void doubleBookingConflicts() throws Exception {
		Instant start = slot();
		assertThat(book(branchA1.getId(), svcA1.getId(), start, customerA1Token)
				.statusCode()).isEqualTo(HttpStatus.CREATED.value());

		// Same service slot taken…
		HttpResponse<String> clash = book(branchA1.getId(), svcA1.getId(), start,
				customerA2Token);
		assertThat(clash.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		JsonNode body = json(clash.body());
		assertThat(body.get("code").asString()).isEqualTo("APPOINTMENT_CONFLICT");
		assertThat(clash.body()).doesNotContain("exclusion");
	}

	@Test
	void offsetStartPreservesInstant() throws Exception {
		// Explicit-offset input keeps exact instant semantics end to end.
		Instant start = ZonedDateTime.now(KOLKATA).plusDays(5).withHour(10).withMinute(0)
				.withSecond(0).withNano(0).toInstant();
		JsonNode body = json(book(branchA1.getId(), svcA1.getId(), start,
				customerA1Token).body());
		assertThat(Instant.parse(body.get("startAt").asString())).isEqualTo(start);
		assertThat(body.get("branchTimeZone").asString()).isEqualTo("Asia/Kolkata");
	}

	// -- Reads ---------------------------------------------------------------------------

	@Test
	void getAppointmentPrivacyTiers() throws Exception {
		String id = json(book(branchA1.getId(), svcA1.getId(), slot(),
				customerA1Token).body()).get("id").asString();

		assertThat(get("/api/v1/appointments/" + id, customerA1Token).statusCode())
				.isEqualTo(HttpStatus.OK.value());
		assertThat(get("/api/v1/appointments/" + id, managerAToken).statusCode())
				.isEqualTo(HttpStatus.OK.value());
		assertThat(get("/api/v1/appointments/" + id, adminAToken).statusCode())
				.isEqualTo(HttpStatus.OK.value());
		assertThat(get("/api/v1/appointments/" + id, staffA1Token).statusCode())
				.isEqualTo(HttpStatus.OK.value());

		// Same-org customers and unassigned staff get 404 — never a peek.
		assertThat(get("/api/v1/appointments/" + id, customerA2Token).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		User otherStaff = newUser(orgA, "other-" + tag, RoleType.STAFF);
		assignments.save(new StaffAssignment(orgA, otherStaff, branchA2));
		assertThat(get("/api/v1/appointments/" + id, tokenFor(otherStaff)).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());

		// Foreign organization: 404 both directions, not 403.
		assertThat(get("/api/v1/appointments/" + id, managerBToken).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(json(get("/api/v1/appointments/" + id, managerBToken).body())
				.get("code").asString()).isEqualTo("APPOINTMENT_NOT_FOUND");
		assertThat(get("/api/v1/appointments/" + UUID.randomUUID(), managerAToken)
				.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void listRoutingByRole() throws Exception {
		book(branchA1.getId(), svcA1.getId(), slot(), customerA1Token);
		book(branchA1.getId(), svcA1.getId(), slot(), customerA2Token);

		// Dedicated customer: exact own-only count, immune to other tests' rows.
		User solo = newUser(orgA, "solo-" + tag, RoleType.CUSTOMER);
		book(branchA1.getId(), svcA1.getId(), slot(), tokenFor(solo));
		JsonNode own = json(get("/api/v1/appointments", tokenFor(solo)).body());
		assertThat(own.get("totalElements").asLong()).isEqualTo(1);
		assertThat(own.get("content").get(0).get("customerId").asString())
				.isEqualTo(solo.getId().toString());

		// Naming another customer is an explicit denial.
		assertThat(get("/api/v1/appointments?customerId=" + customerA2.getId(),
				customerA1Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());

		// Staff must name an assigned branch…
		assertThat(get("/api/v1/appointments", staffA1Token).statusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST.value());
		JsonNode staffPage = json(get(
				"/api/v1/appointments?branchId=" + branchA1.getId(), staffA1Token).body());
		assertThat(staffPage.get("totalElements").asLong()).isGreaterThanOrEqualTo(2);
		// …and unassigned branches deny.
		assertThat(get("/api/v1/appointments?branchId=" + branchA2.getId(),
				staffA1Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());

		// Managers roam the organization; foreign facets are 404s.
		assertThat(json(get("/api/v1/appointments", managerAToken).body())
				.get("totalElements").asLong()).isGreaterThanOrEqualTo(2);
		assertThat(get("/api/v1/appointments?branchId=" + branchB1.getId(),
				managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/appointments?customerId=" + UUID.randomUUID(),
				managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void listPaginationSortFilterAndRange() throws Exception {
		Branch branch = branches.save(new Branch(orgA, "PagedAppt " + tag,
				"Indore", "India", KOLKATA));
		fullWeek(branch);
		Service svc = service(branch, "Paged " + tag, 30);
		List<Instant> starts = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			Instant start = ZonedDateTime.now(KOLKATA).plusDays(12 + i).withHour(10)
					.withMinute(0).withSecond(0).withNano(0).toInstant();
			starts.add(start);
			book(branch.getId(), svc.getId(), start, customerA1Token);
		}
		String scoped = "/api/v1/appointments?branchId=" + branch.getId();

		JsonNode first = json(get(scoped + "&page=0&size=2", managerAToken).body());
		assertThat(first.get("content").size()).isEqualTo(2);
		assertThat(first.get("totalElements").asLong()).isEqualTo(5);
		assertThat(first.get("totalPages").asInt()).isEqualTo(3);
		// Default sort is chronological start time.
		assertThat(Instant.parse(first.get("content").get(0).get("startAt").asString()))
				.isEqualTo(starts.get(0));
		JsonNode beyond = json(get(scoped + "&page=9&size=2", managerAToken).body());
		assertThat(beyond.get("content").size()).isEqualTo(0);

		JsonNode desc = json(
				get(scoped + "&sort=startAt,desc&size=100", managerAToken).body());
		assertThat(Instant.parse(desc.get("content").get(0).get("startAt").asString()))
				.isEqualTo(starts.get(4));
		assertThat(get(scoped + "&sort=bogus,asc", managerAToken).statusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(get(scoped + "&size=101", managerAToken).statusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST.value());

		// Overlapping window selects the middle three.
		JsonNode window = json(get(scoped + "&from=" + starts.get(1).minusSeconds(1)
				+ "&to=" + starts.get(3).plus(Duration.ofMinutes(31)), managerAToken).body());
		assertThat(window.get("totalElements").asLong()).isEqualTo(3);
		assertThat(get(scoped + "&from=" + starts.get(3) + "&to=" + starts.get(1),
				managerAToken).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());

		// Status facet after cancelling one.
		JsonNode one = json(get(scoped + "&size=1", managerAToken).body());
		post("/api/v1/appointments/" + one.get("content").get(0).get("id").asString()
				+ "/cancel", null, customerA1Token);
		JsonNode cancelled = json(
				get(scoped + "&status=CANCELLED&size=100", managerAToken).body());
		assertThat(cancelled.get("totalElements").asLong()).isEqualTo(1);
		assertThat(get(scoped + "&status=BOGUS", managerAToken).statusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST.value());
	}

	@Test
	void emptyListAndCustomerFacet() throws Exception {
		Branch empty = branches.save(new Branch(orgA, "EmptyAppt " + tag,
				"Indore", "India", KOLKATA));
		JsonNode page = json(get(
				"/api/v1/appointments?branchId=" + empty.getId(), managerAToken).body());
		assertThat(page.get("content").size()).isEqualTo(0);
		assertThat(page.get("totalElements").asLong()).isEqualTo(0);

		JsonNode filtered = json(get("/api/v1/appointments?customerId="
				+ customerA1.getId() + "&size=100", managerAToken).body());
		for (JsonNode node : filtered.get("content")) {
			assertThat(node.get("customerId").asString())
					.isEqualTo(customerA1.getId().toString());
		}
	}

	// -- Lifecycle --------------------------------------------------------------------------

	@Test
	void cancelSemantics() throws Exception {
		String id = json(book(branchA1.getId(), svcA1.getId(), slot(),
				customerA1Token).body()).get("id").asString();

		HttpResponse<String> cancelled =
				post("/api/v1/appointments/" + id + "/cancel", null, customerA1Token);
		assertThat(cancelled.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(cancelled.body()).get("status").asString()).isEqualTo("CANCELLED");

		// Terminal states refuse — 409, deliberately not idempotent.
		HttpResponse<String> again =
				post("/api/v1/appointments/" + id + "/cancel", null, customerA1Token);
		assertThat(again.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(json(again.body()).get("code").asString())
				.isEqualTo("APPOINTMENT_INVALID_TRANSITION");

		// The freed slot re-books immediately (exclusions ignore cancelled rows).
		assertThat(book(branchA1.getId(), svcA1.getId(),
				Instant.parse(json(cancelled.body()).get("startAt").asString()),
				customerA2Token).statusCode()).isEqualTo(HttpStatus.CREATED.value());
	}

	@Test
	void cancelAuthorization() throws Exception {
		String id = json(book(branchA1.getId(), svcA1.getId(), slot(),
				customerA1Token).body()).get("id").asString();

		// Another customer: denied without disclosure games on commands.
		assertThat(post("/api/v1/appointments/" + id + "/cancel", null,
				customerA2Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		// Assigned staff cannot cancel others' rows (front-desk deferred).
		assertThat(post("/api/v1/appointments/" + id + "/cancel", null,
				staffA1Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
		// Branch management can.
		assertThat(post("/api/v1/appointments/" + id + "/cancel", null,
				managerAToken).statusCode()).isEqualTo(HttpStatus.OK.value());
		// Foreign organization: 404.
		assertThat(post("/api/v1/appointments/" + id + "/cancel", null,
				managerBToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void completeAndNoShowSemantics() throws Exception {
		String first = json(book(branchA1.getId(), svcA1.getId(), slot(),
				customerA1Token).body()).get("id").asString();
		String second = json(book(branchA1.getId(), svcA1.getId(), slot(),
				customerA1Token).body()).get("id").asString();

		// Assigned staff complete; customers cannot touch outcomes.
		assertThat(json(post("/api/v1/appointments/" + first + "/complete", null,
				staffA1Token).body()).get("status").asString()).isEqualTo("COMPLETED");
		assertThat(post("/api/v1/appointments/" + second + "/complete", null,
				customerA1Token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());

		// Managers mark no-show; terminal rows refuse with 409.
		assertThat(json(post("/api/v1/appointments/" + second + "/no-show", null,
				managerAToken).body()).get("status").asString()).isEqualTo("NO_SHOW");
		assertThat(post("/api/v1/appointments/" + first + "/no-show", null,
				managerAToken).statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(post("/api/v1/appointments/" + first + "/complete", null,
				managerAToken).statusCode()).isEqualTo(HttpStatus.CONFLICT.value());

		// Foreign organization: 404.
		assertThat(post("/api/v1/appointments/" + first + "/complete", null,
				managerBToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(post("/api/v1/appointments/" + UUID.randomUUID() + "/cancel", null,
				managerAToken).statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}
}

