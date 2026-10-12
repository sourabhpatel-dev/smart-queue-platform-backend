package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.auth.AccountVerificationToken;
import com.sourabh.smartqueue.auth.AccountVerificationTokenRepository;
import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.platform.application.OrganizationReviewService;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Fail-closed platform administration without bootstrap configuration
 * (Phase 4R.4, ADR-041).
 *
 * <p>No {@code app.platform.admin-emails} is set here (the production
 * default): even a verified, signed-in user is denied every review
 * call, and the login never attaches the platform role. The operator
 * must configure an address explicitly — the first registered user is
 * never crowned silently.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "app.platform.admin-emails=")
@Transactional
class PlatformAdminMissingConfigTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	UserRepository users;
	@Autowired
	PasswordCredentialService credentials;
	@Autowired
	AccountVerificationTokenRepository verifications;
	@Autowired
	AuthService authService;
	@Autowired
	OrganizationReviewService review;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	@Test
	void missingConfigurationDeniesEveryoneAndGrantsNoRole() {
		String address = "first-user-" + UUID.randomUUID() + "@example.com";
		User user = new User(address, "First User");
		user.grantRole(RoleType.CUSTOMER);
		users.save(user);
		credentials.setPassword(user.getId(), "first-pass-1");
		AccountVerificationToken token = new AccountVerificationToken(user,
				"missing-config-" + UUID.randomUUID(), Instant.now().plusSeconds(86400));
		token.markUsed(Instant.now());
		verifications.save(token);

		AuthService.LoginResult login = authService.login(address, "first-pass-1");
		assertThat(login.principal().roles()).doesNotContain(RoleType.PLATFORM_ADMIN);

		AuthenticatedPrincipal principal = login.principal();
		assertThat(principal.roles()).containsExactly(RoleType.CUSTOMER);
		assertThatThrownBy(() -> review.listApplications(principal, null))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> review.approve(principal, UUID.randomUUID()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> review.reject(principal, UUID.randomUUID(), "no"))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void invalidConfigurationEntriesAreIgnored() {
		// Bound through the setter the way Boot binds the property:
		// blanks and non-emails are dropped, so they can never match.
		com.sourabh.smartqueue.platform.PlatformAdminProperties properties =
				new com.sourabh.smartqueue.platform.PlatformAdminProperties();
		properties.setAdminEmails(Set.of("  ", "not-an-email", "", "Admin@Example.COM "));
		assertThat(properties.getAdminEmails()).containsExactly("admin@example.com");
		assertThat(properties.isConfiguredAdmin("ADMIN@example.com")).isTrue();
		assertThat(properties.isConfiguredAdmin(null)).isFalse();
		assertThat(properties.isConfiguredAdmin("stranger@example.com")).isFalse();
	}
}
