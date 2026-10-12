package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.auth.AccountVerificationToken;
import com.sourabh.smartqueue.auth.AccountVerificationTokenRepository;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.auth.RateLimiter;
import com.sourabh.smartqueue.email.EmailMessage;
import com.sourabh.smartqueue.email.EmailSender;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Organization onboarding over real HTTP (Phase 4R.4, ADR-041).
 *
 * <p>Black-box through the filter chain: public submission ignores
 * injected status/role fields, unauthenticated calls are 401,
 * non-platform roles are 403, and the configured administrator drives
 * approve/reject end to end. The sender and rate limiter are mocked
 * (tokens are captured from the mock); PostgreSQL is real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.platform.admin-emails=api-admin@example.com")
class OrganizationOnboardingApiTests {

	static final String ADMIN_EMAIL = "api-admin@example.com";
	static final String ADMIN_PASSWORD = "Api-Admin-Pass-1";
	static final Pattern TOKEN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@TestConfiguration
	static class MockConfiguration {
		@Bean
		@Primary
		EmailSender mockEmailSender() {
			return Mockito.mock(EmailSender.class);
		}

		@Bean
		@Primary
		RateLimiter allowAllRateLimiter() {
			return Mockito.mock(RateLimiter.class);
		}
	}

	@LocalServerPort
	int port;

	@Autowired
	OrganizationRepository organizations;
	@Autowired
	UserRepository users;
	@Autowired
	PasswordCredentialService credentials;
	@Autowired
	AccountVerificationTokenRepository verifications;
	@Autowired
	OrganizationMembershipRepository memberships;
	@Autowired
	EmailSender sender;
	@Autowired
	ObjectMapper objectMapper;
	@Autowired
	PlatformTransactionManager transactionManager;

	private final HttpClient http = HttpClient.newHttpClient();
	private static volatile boolean adminSeeded;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		Mockito.clearInvocations(sender);
		seedAdminOnce();
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private synchronized void seedAdminOnce() {
		if (adminSeeded) {
			return;
		}
		new TransactionTemplate(transactionManager).execute(status -> {
			User admin = new User(ADMIN_EMAIL, "API Admin");
			admin.grantRole(RoleType.CUSTOMER);
			users.save(admin);
			credentials.setPassword(admin.getId(), ADMIN_PASSWORD);
			AccountVerificationToken token = new AccountVerificationToken(admin,
					"seed-api-admin-" + UUID.randomUUID(),
					Instant.now().plusSeconds(86400));
			token.markUsed(Instant.now());
			verifications.save(token);
			return null;
		});
		adminSeeded = true;
	}

	private String email(String tag) {
		return "api-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

	private String lastEmailedToken() {
		ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
		Mockito.verify(sender, Mockito.atLeastOnce()).send(captor.capture());
		Matcher matcher = TOKEN_LINK.matcher(captor.getValue().bodyText());
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	private HttpResponse<String> post(String path, String body, String token) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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

	private String businessBody(String orgName, String ownerEmail) {
		return "{\"organizationName\":\"" + orgName + "\","
				+ "\"ownerName\":\"Owner\","
				+ "\"ownerEmail\":\"" + ownerEmail + "\","
				+ "\"password\":\"owner-pass-1\","
				+ "\"branchName\":\"Main\","
				+ "\"city\":\"Bhopal\","
				+ "\"country\":\"India\","
				+ "\"timeZone\":\"Asia/Kolkata\"}";
	}

	private String adminToken() throws Exception {
		HttpResponse<String> login = post("/api/v1/auth/login",
				"{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}",
				null);
		assertThat(login.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(login.body());
		assertThat(body.get("roles").toString()).contains("PLATFORM_ADMIN");
		return body.get("accessToken").asString();
	}

	private String registerVerifyAndLogin(String displayName, String address) throws Exception {
		HttpResponse<String> registered = post("/api/v1/auth/register",
				"{\"displayName\":\"" + displayName + "\",\"email\":\"" + address
						+ "\",\"password\":\"customer-pass-1\"}",
				null);
		assertThat(registered.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(post("/api/v1/auth/verify-email",
				"{\"token\":\"" + lastEmailedToken() + "\"}", null).statusCode())
				.isEqualTo(HttpStatus.OK.value());
		HttpResponse<String> login = post("/api/v1/auth/login",
				"{\"email\":\"" + address + "\",\"password\":\"customer-pass-1\"}", null);
		assertThat(login.statusCode()).isEqualTo(HttpStatus.OK.value());
		return json(login.body()).get("accessToken").asString();
	}

	@Test
	void businessSubmissionIsPendingAndIgnoresInjectedStatusAndRoles() throws Exception {
		String orgName = "Injected Clinic " + UUID.randomUUID();
		String withInjection = "{\"organizationName\":\"" + orgName + "\","
				+ "\"ownerName\":\"Owner\","
				+ "\"ownerEmail\":\"" + email("inject") + "\","
				+ "\"password\":\"owner-pass-1\","
				+ "\"branchName\":\"Main\","
				+ "\"city\":\"Bhopal\","
				+ "\"country\":\"India\","
				+ "\"timeZone\":\"Asia/Kolkata\","
				+ "\"status\":\"ACTIVE\",\"roles\":[\"PLATFORM_ADMIN\"],"
				+ "\"organizationId\":\"" + UUID.randomUUID() + "\"}";

		HttpResponse<String> response =
				post("/api/v1/auth/register/business", withInjection, null);
		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		String orgId = json(response.body()).get("organizationId").asString();

		// Still pending: unknown fields are ignored, never trusted.
		String token = adminToken();
		HttpResponse<String> pending = get(
				"/api/v1/platform/organizations?status=PENDING_APPROVAL", token);
		assertThat(pending.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(pending.body()).contains(orgId);
		assertThat(get("/api/v1/discovery/organizations", null).body())
				.doesNotContain(orgName);
	}

	@Test
	void personalEmailProviderApplicationIsAccepted() throws Exception {
		String address = "local.shop." + UUID.randomUUID().toString().substring(0, 8)
				+ "@gmail.com";
		HttpResponse<String> response = post("/api/v1/auth/register/business",
				businessBody("Neighborhood Store " + UUID.randomUUID(), address), null);
		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(response.body()).get("verificationRequired").asBoolean()).isTrue();
	}

	@Test
	void unauthenticatedStatusAndReviewAre401() throws Exception {
		assertThat(get("/api/v1/organizations/applications/mine", null).statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());
		assertThat(get("/api/v1/platform/organizations", null).statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());
		assertThat(post("/api/v1/platform/organizations/" + UUID.randomUUID() + "/approve",
				null, null).statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	@Test
	void customerGets404OnMineAnd403OnReview() throws Exception {
		String token = registerVerifyAndLogin("Api Customer", email("cust"));

		assertThat(get("/api/v1/organizations/applications/mine", token).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(get("/api/v1/platform/organizations", token).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post("/api/v1/platform/organizations/" + UUID.randomUUID() + "/approve",
				null, token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	@Test
	void organizationAdminGets403OnReview() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		Organization org = organizations.save(new Organization("Seeded Org " + tag));
		String address = "seeded-admin-" + tag + "@example.com";
		new TransactionTemplate(transactionManager).execute(status -> {
			User admin = new User(org, address, "Seeded Admin");
			admin.grantRole(RoleType.ADMIN);
			users.save(admin);
			memberships.save(new OrganizationMembership(admin, org, MembershipRole.ADMIN));
			credentials.setPassword(admin.getId(), "Seeded-Admin-1");
			return null;
		});
		HttpResponse<String> login = post("/api/v1/auth/login",
				"{\"email\":\"" + address + "\",\"password\":\"Seeded-Admin-1\"}", null);
		assertThat(login.statusCode()).isEqualTo(HttpStatus.OK.value());
		String token = json(login.body()).get("accessToken").asString();

		assertThat(get("/api/v1/platform/organizations", token).statusCode())
				.isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(post("/api/v1/platform/organizations/" + UUID.randomUUID() + "/approve",
				null, token).statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	@Test
	void adminApprovesAndRejectsEndToEnd() throws Exception {
		// Application A → approve.
		String orgA = "Approval Clinic " + UUID.randomUUID();
		String ownerA = email("owner-a");
		HttpResponse<String> submittedA = post("/api/v1/auth/register/business",
				businessBody(orgA, ownerA), null);
		assertThat(submittedA.statusCode()).isEqualTo(HttpStatus.OK.value());
		String orgAId = json(submittedA.body()).get("organizationId").asString();
		assertThat(post("/api/v1/auth/verify-email",
				"{\"token\":\"" + lastEmailedToken() + "\"}", null).statusCode())
				.isEqualTo(HttpStatus.OK.value());

		String token = adminToken();
		HttpResponse<String> pending = get(
				"/api/v1/platform/organizations?status=PENDING_APPROVAL", token);
		assertThat(pending.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(pending.body()).contains(orgAId);

		HttpResponse<String> approved =
				post("/api/v1/platform/organizations/" + orgAId + "/approve", null, token);
		assertThat(approved.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(approved.body()).get("status").asString()).isEqualTo("ACTIVE");
		assertThat(get("/api/v1/discovery/organizations", null).body()).contains(orgA);
		// Re-deciding is a safe 409, not a silent no-op.
		assertThat(post("/api/v1/platform/organizations/" + orgAId + "/approve", null, token)
				.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());

		// Application B → reject with applicant-facing feedback.
		String ownerB = email("owner-b");
		String orgBId = json(post("/api/v1/auth/register/business",
				businessBody("Rejection Clinic " + UUID.randomUUID(), ownerB), null)
				.body()).get("organizationId").asString();
		assertThat(post("/api/v1/auth/verify-email",
				"{\"token\":\"" + lastEmailedToken() + "\"}", null).statusCode())
				.isEqualTo(HttpStatus.OK.value());
		HttpResponse<String> rejected = post(
				"/api/v1/platform/organizations/" + orgBId + "/reject",
				"{\"reason\":\"Please resubmit with a clearer trade license copy.\"}", token);
		assertThat(rejected.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(rejected.body()).get("status").asString()).isEqualTo("REJECTED");
		assertThat(json(rejected.body()).get("rejectionReason").asString())
				.contains("trade license");
		assertThat(get("/api/v1/discovery/organizations", null).body())
				.doesNotContain(orgBId);

		// Owner B sees the rejection and its reason on their own status.
		HttpResponse<String> loginB = post("/api/v1/auth/login",
				"{\"email\":\"" + ownerB + "\",\"password\":\"owner-pass-1\"}", null);
		assertThat(loginB.statusCode()).isEqualTo(HttpStatus.OK.value());
		HttpResponse<String> mine = get("/api/v1/organizations/applications/mine",
				json(loginB.body()).get("accessToken").asString());
		assertThat(mine.statusCode()).isEqualTo(HttpStatus.OK.value());
		assertThat(json(mine.body()).get("status").asString()).isEqualTo("REJECTED");
		assertThat(json(mine.body()).get("rejectionReason").asString())
				.contains("trade license");
	}

	@Test
	void unverifiedApplicationCannotBeApproved() throws Exception {
		String orgId = json(post("/api/v1/auth/register/business",
				businessBody("Unverified Clinic " + UUID.randomUUID(), email("owner-u")), null)
				.body()).get("organizationId").asString();
		HttpResponse<String> response = post(
				"/api/v1/platform/organizations/" + orgId + "/approve", null, adminToken());
		assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(json(response.body()).get("code").asString())
				.isEqualTo("INVALID_TRANSITION");
	}
}
