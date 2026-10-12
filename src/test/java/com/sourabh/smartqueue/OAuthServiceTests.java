package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.auth.AccountVerificationToken;
import com.sourabh.smartqueue.auth.AccountVerificationTokenRepository;
import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.OauthIdentityRepository;
import com.sourabh.smartqueue.auth.oauth.OAuthService;
import com.sourabh.smartqueue.auth.oauth.OauthProvider;
import com.sourabh.smartqueue.auth.oauth.VerifiedIdentity;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.organization.OrganizationStatus;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.user.UserStatus;

/**
 * Social account-linking policy (see the OAuth decision notes).
 * Exercises {@code loginWithIdentity} directly — transport is a
 * separate seam with its own tests, so no network runs here. Each
 * test rolls back.
 */
@Testcontainers
@SpringBootTest
@Transactional
class OAuthServiceTests {

	static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

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

	@Autowired
	OrganizationRepository organizations;
	@Autowired
	UserRepository users;
	@Autowired
	OauthIdentityRepository identities;
	@Autowired
	AccountVerificationTokenRepository verifications;
	@Autowired
	OAuthService oauth;

	Organization org;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		org = organizations.save(new Organization("OAuth Org " + UUID.randomUUID()));
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private User user(String local, RoleType role, UserStatus status) {
		User user = users.save(new User(org, local + "@example.com", local));
		user.grantRole(role);
		user.setStatus(status);
		return users.save(user);
	}

	private VerifiedIdentity identity(String subject, String email, boolean verified) {
		return new VerifiedIdentity(subject, email, verified);
	}

	@Test
	void knownLinkLogsIn() {
		User user = user("known-" + UUID.randomUUID(), RoleType.CUSTOMER, UserStatus.ACTIVE);
		oauth.loginWithIdentity(OauthProvider.GOOGLE,
				identity("google-sub-1", user.getEmail(), true), null);

		AuthService.LoginResult result = oauth.loginWithIdentity(OauthProvider.GOOGLE,
				identity("google-sub-1", user.getEmail(), true), null);

		assertThat(result.principal().userId()).isEqualTo(user.getId());
		// ADR-040: customers are platform-level identities — the linked
		// account's legacy organization never enters the principal.
		assertThat(result.principal().organizationId()).isNull();
		assertThat(identities.findByProviderAndSubject("GOOGLE", "google-sub-1")).isPresent();
	}

	@Test
	void verifiedEmailMatchLinksAndConsumesVerification() {
		User user = user("match-" + UUID.randomUUID(), RoleType.CUSTOMER, UserStatus.ACTIVE);
		verifications.save(new AccountVerificationToken(user, "digest",
				NOW.plus(java.time.Duration.ofHours(1))));

		AuthService.LoginResult result = oauth.loginWithIdentity(OauthProvider.GOOGLE,
				identity("google-sub-2", user.getEmail(), true), null);

		assertThat(result.principal().userId()).isEqualTo(user.getId());
		assertThat(identities.findByProviderAndSubject("GOOGLE", "google-sub-2")).isPresent();
		assertThat(verifications.findByUserId(user.getId()).orElseThrow().getUsedAt())
				.isEqualTo(NOW);
	}

	@Test
	void inactiveOrUnverifiedMatchesAreGenericFailures() {
		User inactive = user("off-" + UUID.randomUUID(), RoleType.CUSTOMER, UserStatus.INACTIVE);
		User active = user("on-" + UUID.randomUUID(), RoleType.CUSTOMER, UserStatus.ACTIVE);

		assertThatThrownBy(() -> oauth.loginWithIdentity(OauthProvider.GOOGLE,
				identity("google-sub-3", inactive.getEmail(), true), null))
				.isInstanceOf(BadCredentialsException.class);
		assertThatThrownBy(() -> oauth.loginWithIdentity(OauthProvider.GOOGLE,
				identity("google-sub-4", active.getEmail(), false), null))
				.isInstanceOf(BadCredentialsException.class);
		assertThat(identities.findByProviderAndSubject("GOOGLE", "google-sub-4")).isEmpty();
	}

	@Test
	void newVerifiedEmailProvisionsCustomerInChosenOrg() {
		AuthService.LoginResult result = oauth.loginWithIdentity(OauthProvider.APPLE,
				identity("apple-sub-9", "fresh-" + UUID.randomUUID() + "@example.com", true),
				org.getId());

		// Provisioning still anchors the account at the chosen organization
		// (current contract, unchanged), but the principal is platform-level
		// (ADR-040): the account's legacy value never authorizes.
		assertThat(result.principal().organizationId()).isNull();
		User created = users.findByEmail(
				users.findById(result.principal().userId()).orElseThrow().getEmail())
				.orElseThrow();
		assertThat(created.getRoles()).containsExactly(RoleType.CUSTOMER);
		assertThat(identities.findByProviderAndSubject("APPLE", "apple-sub-9")).isPresent();
	}

	@Test
	void newEmailRequiresVerifiedProviderEmailAndLiveOrg() {
		assertThatThrownBy(() -> oauth.loginWithIdentity(OauthProvider.GOOGLE,
				identity("google-sub-5", "new-" + UUID.randomUUID() + "@example.com", false),
				org.getId()))
				.isInstanceOf(BadCredentialsException.class);
		assertThatThrownBy(() -> oauth.loginWithIdentity(OauthProvider.GOOGLE,
				identity("google-sub-6", "new-" + UUID.randomUUID() + "@example.com", true),
				UUID.randomUUID()))
				.isInstanceOf(ResourceNotFoundException.class);
		Organization closed = organizations.save(new Organization("Closed " + UUID.randomUUID()));
		closed.setStatus(OrganizationStatus.INACTIVE);
		organizations.save(closed);
		assertThatThrownBy(() -> oauth.loginWithIdentity(OauthProvider.GOOGLE,
				identity("google-sub-7", "new-" + UUID.randomUUID() + "@example.com", true),
				closed.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void unknownProviderIs400() {
		assertThatThrownBy(() -> oauth.authenticate("FACEBOOK", "code", "https://app/cb", null))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void disabledProviderIs400() {
		// No OAuth credentials configured in tests: both providers
		// stay disabled by design.
		assertThat(oauth.enabledProviders()).isEmpty();
		assertThatThrownBy(() -> oauth.authenticate("GOOGLE", "code", "https://app/cb", null))
				.isInstanceOf(InvalidRequestException.class);
	}
}
