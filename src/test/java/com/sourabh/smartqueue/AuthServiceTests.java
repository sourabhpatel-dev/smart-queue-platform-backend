package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.JwtService;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.auth.UserCredentialRepository;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.MembershipStatus;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.user.UserStatus;

/**
 * Authentication flow tests (see ADR-021): login success and the full
 * failure matrix. Time is fixed through the project Clock. Each test rolls
 * back. Own container per test class.
 */
@SpringBootTest
@Testcontainers
@Transactional
class AuthServiceTests {

	static final Instant NOW = Instant.parse("2026-10-06T04:30:00Z");
	static final String PASSWORD = "Correct-Horse-99";

	static class ManualClock extends Clock {
		private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

		@Override
		public Instant instant() {
			return now.get();
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException("fixed test clock");
		}
	}

	@TestConfiguration
	static class FixedClockConfiguration {
		@Bean
		@Primary
		ManualClock manualClock() {
			return new ManualClock();
		}
	}

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	OrganizationRepository organizations;

	@Autowired
	UserRepository users;

	@Autowired
	AuthService authService;

	@Autowired
	PasswordCredentialService credentials;

	@Autowired
	OrganizationMembershipRepository memberships;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	UserCredentialRepository credentialRepository;

	@Autowired
	JwtService jwtService;

	Organization org;
	User user;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		org = organizations.save(new Organization("Auth Clinic " + UUID.randomUUID()));
		user = users.save(new User(org,
				"auth-" + UUID.randomUUID() + "@example.com", "Auth User"));
		user.grantRole(RoleType.STAFF);
		users.save(user);
		// The V20 backfill is what grounds this user in production; the
		// login resolves the organization from the membership row (ADR-040).
		memberships.save(new OrganizationMembership(user, org, MembershipRole.STAFF));
		credentials.setPassword(user.getId(), PASSWORD);
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	@Test
	void validCredentialsIssueToken() {
		AuthService.LoginResult result = authService.login(user.getEmail(), PASSWORD);

		assertThat(result.token()).isNotBlank();
		assertThat(result.userId()).isEqualTo(user.getId());
		assertThat(result.organizationId()).isEqualTo(org.getId());
		assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
		assertThat(result.principal().roles()).containsExactly(RoleType.STAFF);
		assertThat(result.principal().email()).isEqualTo(user.getEmail());

		// The issued token validates back to the same principal.
		assertThat(jwtService.validateToken(result.token())).isEqualTo(result.principal());
	}

	@Test
	void emailLookupIsCaseInsensitive() {
		AuthService.LoginResult result =
				authService.login(user.getEmail().toUpperCase(), PASSWORD);

		assertThat(result.userId()).isEqualTo(user.getId());
	}

	@Test
	void wrongPasswordFailsGenerically() {
		assertThatThrownBy(() -> authService.login(user.getEmail(), "Wrong-Password-1"))
				.isInstanceOf(BadCredentialsException.class)
				.hasMessage(AuthService.GENERIC_FAILURE);
	}

	@Test
	void unknownUserFailsIdentically() {
		assertThatThrownBy(() -> authService.login("nobody@example.com", PASSWORD))
				.isInstanceOf(BadCredentialsException.class)
				.hasMessage(AuthService.GENERIC_FAILURE);
	}

	@Test
	void inactiveUserFailsIdentically() {
		user.setStatus(UserStatus.INACTIVE);
		users.save(user);

		assertThatThrownBy(() -> authService.login(user.getEmail(), PASSWORD))
				.isInstanceOf(BadCredentialsException.class)
				.hasMessage(AuthService.GENERIC_FAILURE);
	}

	@Test
	void userWithoutCredentialFailsIdentically() {
		User bare = users.save(new User(org,
				"bare-" + UUID.randomUUID() + "@example.com", "Bare User"));

		assertThatThrownBy(() -> authService.login(bare.getEmail(), PASSWORD))
				.isInstanceOf(BadCredentialsException.class)
				.hasMessage(AuthService.GENERIC_FAILURE);
	}

	@Test
	void storedHashIsNotPlaintext() {
		String hash = credentialRepository.findByUserId(user.getId())
				.orElseThrow().getPasswordHash();
		assertThat(hash).isNotEqualTo(PASSWORD);
		assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
	}

	@Test
	void platformLevelCustomerLogsInWithNullOrganization() {
		User customer = users.save(new User(
				"customer-" + UUID.randomUUID() + "@example.com", "Customer"));
		customer.grantRole(RoleType.CUSTOMER);
		users.save(customer);
		credentials.setPassword(customer.getId(), PASSWORD);

		AuthService.LoginResult result = authService.login(customer.getEmail(), PASSWORD);

		// No membership is required and none is consulted.
		assertThat(result.token()).isNotBlank();
		assertThat(result.userId()).isEqualTo(customer.getId());
		assertThat(result.organizationId()).isNull();
		assertThat(result.principal().organizationId()).isNull();
		assertThat(result.principal().roles()).containsExactly(RoleType.CUSTOMER);

		// The issued token has no org claim and validates back identically.
		AuthenticatedPrincipal back = jwtService.validateToken(result.token());
		assertThat(back).isEqualTo(result.principal());
		assertThat(back.organizationId()).isNull();
	}

	@Test
	void legacyOrganizationValueNeverAuthorizesByItself() {
		// A legacy home organization with no membership resolves to no
		// organization — the value is only a tie-breaker among ACTIVE
		// memberships (ADR-040).
		User legacyStaff = users.save(new User(org,
				"legacy-" + UUID.randomUUID() + "@example.com", "Legacy"));
		legacyStaff.grantRole(RoleType.STAFF);
		users.save(legacyStaff);
		credentials.setPassword(legacyStaff.getId(), PASSWORD);

		AuthService.LoginResult result = authService.login(legacyStaff.getEmail(), PASSWORD);

		assertThat(result.userId()).isEqualTo(legacyStaff.getId());
		assertThat(result.organizationId()).isNull();
	}

	@Test
	void onboardingOperationalUserResolvesMembershipOrganization() {
		User staffOAuth = users.save(new User(
				"staff-oauth-" + UUID.randomUUID() + "@example.com", "Staff O"));
		staffOAuth.grantRole(RoleType.STAFF);
		users.save(staffOAuth);
		memberships.save(new OrganizationMembership(staffOAuth, org, MembershipRole.STAFF));
		credentials.setPassword(staffOAuth.getId(), PASSWORD);

		AuthService.LoginResult result = authService.login(staffOAuth.getEmail(), PASSWORD);

		assertThat(result.organizationId()).isEqualTo(org.getId());
	}

	@Test
	void inactiveMembershipResolvesToNoOrganization() {
		OrganizationMembership membership = memberships
				.findByUserIdAndOrganizationId(user.getId(), org.getId()).orElseThrow();
		membership.setStatus(MembershipStatus.INACTIVE);
		memberships.save(membership);

		// Login itself still succeeds (credentials are the gate); the
		// principal carries no organization for authorization to build on.
		AuthService.LoginResult result = authService.login(user.getEmail(), PASSWORD);
		assertThat(result.organizationId()).isNull();
	}

	@Test
	void blankPasswordIsRejectedAtProvisioning() {
		assertThatThrownBy(() -> credentials.setPassword(user.getId(), "  "))
				.isInstanceOf(IllegalArgumentException.class);
	}
}


