package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.user.UserStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Login endpoint tests over real HTTP (see ADR-021): success shape,
 * generic 401s that are identical across failure modes (no account
 * enumeration), malformed bodies, and the filter chain's posture on
 * unknown paths (401 anonymous, 404 authenticated-but-unmapped).
 *
 * <p>Plain {@code java.net.http} against {@code RANDOM_PORT}: true
 * black-box HTTP through the real filter chain (JWT filter, tenant
 * filter, entry point) with no MockMvc machinery. Non-transactional so
 * the server thread observes committed fixtures; unique data per test
 * inside this class's own containers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthControllerTests {

	static final String PASSWORD = "Controller-Password-7";

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

	Organization org;
	User user;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		org = organizations.save(new Organization("Login Clinic " + UUID.randomUUID()));
		user = users.save(new User(org,
				"login-" + UUID.randomUUID() + "@example.com", "Login User"));
		// Non-transactional: the entity is detached after save, so the grant
		// must be saved explicitly (no dirty-checking flush here).
		user.grantRole(RoleType.STAFF);
		user = users.save(user);
		// Mirrors the V20 backfill: login resolves the organization from
		// the ACTIVE membership (ADR-040).
		memberships.save(new OrganizationMembership(user, org, MembershipRole.STAFF));
		credentials.setPassword(user.getId(), PASSWORD);
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private HttpResponse<String> postLogin(String body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
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

	@Test
	void validLoginReturnsBearerToken() throws Exception {
		HttpResponse<String> response = postLogin(
				"{\"email\":\"" + user.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}");

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
		JsonNode body = json(response.body());
		assertThat(body.get("tokenType").asString()).isEqualTo("Bearer");
		assertThat(body.get("accessToken").asString()).isNotBlank();
		assertThat(body.get("expiresAt").asString()).isNotBlank();
		assertThat(body.get("userId").asString()).isEqualTo(user.getId().toString());
		assertThat(body.get("organizationId").asString()).isEqualTo(org.getId().toString());
		assertThat(body.get("roles").toString()).contains("STAFF");
		assertThat(response.body()).doesNotContain("passwordHash");
	}

	@Test
	void authenticationFailuresAreIndistinguishable() throws Exception {
		HttpResponse<String> wrongPassword = postLogin(
				"{\"email\":\"" + user.getEmail() + "\",\"password\":\"Wrong-Password-1\"}");
		assertThat(wrongPassword.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
		assertThat(json(wrongPassword.body()).get("message").asString())
				.isEqualTo(AuthService.GENERIC_FAILURE);

		HttpResponse<String> unknownUser = postLogin(
				"{\"email\":\"nobody@example.com\",\"password\":\"" + PASSWORD + "\"}");
		assertThat(unknownUser.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());

		user.setStatus(UserStatus.INACTIVE);
		users.save(user);
		HttpResponse<String> inactiveUser = postLogin(
				"{\"email\":\"" + user.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}");
		assertThat(inactiveUser.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());

		// No account-enumeration signal: identical status/code/message across
		// failure modes. (Phase 3G renders login failures in the shared
		// ApiError envelope, whose per-response timestamp legitimately varies
		// — the security property is the identical status/code/message, which
		// carries no caller-dependent signal. See ADR-022.)
		for (HttpResponse<String> response : new HttpResponse[]{unknownUser, inactiveUser}) {
			assertThat(json(response.body()).get("status").asInt())
					.isEqualTo(json(wrongPassword.body()).get("status").asInt());
			assertThat(json(response.body()).get("code").asString())
					.isEqualTo(json(wrongPassword.body()).get("code").asString());
			assertThat(json(response.body()).get("message").asString())
					.isEqualTo(json(wrongPassword.body()).get("message").asString());
		}
	}

	@Test
	void malformedBodyIs400() throws Exception {
		assertThat(postLogin(
				"{\"email\":\"not-an-email\",\"password\":\"" + PASSWORD + "\"}")
				.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(postLogin("{\"email\":\"" + user.getEmail() + "\"}")
				.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
	}

	@Test
	void unknownPathIs401AnonymousAnd404Authenticated() throws Exception {
		assertThat(get("/api/v1/queues", null).statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());
		assertThat(get("/api/v1/queues", "not-a-token").statusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());

		String token = authService.login(user.getEmail(), PASSWORD).token();
		assertThat(get("/api/v1/queues", token).statusCode())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void healthStaysPublic() throws Exception {
		assertThat(get("/actuator/health", null).statusCode())
				.isEqualTo(HttpStatus.OK.value());
	}
}




