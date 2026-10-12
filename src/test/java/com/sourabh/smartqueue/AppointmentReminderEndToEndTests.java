package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.sourabh.smartqueue.appointment.Appointment;
import com.sourabh.smartqueue.appointment.AppointmentRepository;
import com.sourabh.smartqueue.appointment.reminder.application.AppointmentReminderService;
import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Reminder end to end (see ADR-034): a stored appointment, the clock
 * at due time, one sweep — then the reminder is visible through the
 * unchanged notification REST surface (plus its cancellation
 * counterpart, which yields nothing).
 *
 * <p>NOT transactional: the HTTP server thread cannot see uncommitted
 * test transactions, so fixtures commit for real (the documented
 * black-box lesson). Each test mints a fresh customer, so timelines
 * never leak across tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AppointmentReminderEndToEndTests {

	static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	private static final String PASSWORD = "correct-horse-9";

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@TestConfiguration
	static class FixedClockConfiguration {
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

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
	AppointmentRepository appointments;
	@Autowired
	AppointmentReminderService reminderService;
	@Autowired
	AuthService authService;
	@Autowired
	PasswordCredentialService credentials;
	@Autowired
	ObjectMapper objectMapper;

	private final HttpClient http = HttpClient.newHttpClient();

	Organization org;
	Branch branch;
	com.sourabh.smartqueue.service.Service consultation;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		org = organizations.save(new Organization("E2E Org " + tag));
		branch = branches.save(new Branch(org, "Downtown " + tag, "Bhopal", "India", KOLKATA));
		consultation = services.save(new com.sourabh.smartqueue.service.Service(
				branch, "Consultation", Duration.ofMinutes(30)));
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private User customer(String tag) {
		User user = users.save(new User(org, "e2e-" + tag + "@example.com", "E2E " + tag));
		user.grantRole(RoleType.CUSTOMER);
		user = users.save(user);
		credentials.setPassword(user.getId(), PASSWORD);
		return user;
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		return http.send(HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path)).GET()
				.header("Authorization", "Bearer " + token)
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void dueAppointmentAppearsInTheCustomerNotificationTimeline() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User user = customer(tag);
		String token = authService.login(user.getEmail(), PASSWORD).token();
		Instant start = NOW.plus(Duration.ofHours(23));
		Appointment due = appointments.save(new Appointment(org, branch, consultation,
				user, start, start.plus(Duration.ofMinutes(30))));

		reminderService.sweepOnce();

		HttpResponse<String> list = get("/api/v1/notifications", token);
		assertThat(list.statusCode()).isEqualTo(200);
		JsonNode content = objectMapper.readTree(list.body()).get("content");
		assertThat(content.size()).isEqualTo(1);
		JsonNode row = content.get(0);
		assertThat(row.get("type").asText()).isEqualTo("APPOINTMENT_REMINDER");
		assertThat(row.get("title").asText()).isEqualTo("Appointment reminder");
		assertThat(row.get("message").asText()).contains("Consultation");
		assertThat(row.get("referenceType").asText()).isEqualTo("APPOINTMENT");
		assertThat(row.get("referenceId").asText()).isEqualTo(due.getId().toString());
		assertThat(row.get("read").asBoolean()).isFalse();

		HttpResponse<String> count = get("/api/v1/notifications/unread-count", token);
		assertThat(count.statusCode()).isEqualTo(200);
		assertThat(objectMapper.readTree(count.body()).get("unreadCount").asLong())
				.isEqualTo(1);
	}

	@Test
	void cancelledAppointmentYieldsNoReminder() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User user = customer(tag);
		String token = authService.login(user.getEmail(), PASSWORD).token();
		Instant start = NOW.plus(Duration.ofHours(2));
		Appointment due = appointments.save(new Appointment(org, branch, consultation,
				user, start, start.plus(Duration.ofMinutes(30))));
		due.cancel();
		appointments.save(due);

		reminderService.sweepOnce();

		HttpResponse<String> list = get("/api/v1/notifications", token);
		assertThat(list.statusCode()).isEqualTo(200);
		assertThat(objectMapper.readTree(list.body()).get("content").size()).isEqualTo(0);
	}
}
