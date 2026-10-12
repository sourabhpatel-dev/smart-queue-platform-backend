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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.BadCredentialsException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.auth.PasswordResetTokenRepository;
import com.sourabh.smartqueue.auth.SessionRevocationService;
import com.sourabh.smartqueue.auth.application.PasswordRecoveryService;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.email.EmailMessage;
import com.sourabh.smartqueue.email.EmailSender;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Password recovery, change, and session revocation (see ADR-037).
 *
 * <p>NOT transactional: reset tokens arrive only by email (digests
 * persist), and after-commit delivery fires on real commits.
 * Fixtures are unique per test. Time rides a manual project Clock.
 */
@Testcontainers
@SpringBootTest
class PasswordRecoveryServiceTests {

	static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
	static final Pattern TOKEN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@TestConfiguration
	static class ManualClockConfiguration {
		static final AtomicReference<Instant> NOW_REF = new AtomicReference<>(NOW);

		@Bean
		@Primary
		Clock manualClock() {
			return new Clock() {
				@Override
				public ZoneId getZone() {
					return ZoneOffset.UTC;
				}

				@Override
				public Clock withZone(ZoneId zone) {
					throw new UnsupportedOperationException("test clock");
				}

				@Override
				public Instant instant() {
					return NOW_REF.get();
				}
			};
		}

		@Bean
		@Primary
		EmailSender mockEmailSender() {
			return Mockito.mock(EmailSender.class);
		}
	}

	@Autowired
	OrganizationRepository organizations;
	@Autowired
	UserRepository users;
	@Autowired
	PasswordCredentialService credentials;
	@Autowired
	PasswordResetTokenRepository resets;
	@Autowired
	PasswordRecoveryService recovery;
	@Autowired
	SessionRevocationService revocations;
	@Autowired
	AuthService authService;
	@Autowired
	EmailSender sender;

	Organization org;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		ManualClockConfiguration.NOW_REF.set(NOW);
		Mockito.clearInvocations(sender);
		org = organizations.save(new Organization("Recovery " + UUID.randomUUID()));
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
		ManualClockConfiguration.NOW_REF.set(NOW);
	}

	private User customer(String tag, String password) {
		String address = "rec-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8)
				+ "@example.com";
		User user = users.save(new User(org, address, "Rec " + tag));
		user.grantRole(RoleType.CUSTOMER);
		user = users.save(user);
		credentials.setPassword(user.getId(), password);
		return user;
	}

	private String lastEmailedToken() {
		ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
		Mockito.verify(sender, Mockito.atLeastOnce()).send(captor.capture());
		Matcher matcher = TOKEN_LINK.matcher(captor.getValue().bodyText());
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	@Test
	void requestIsGenericForUnknownEmails() {
		recovery.requestReset("nobody-" + UUID.randomUUID() + "@example.com", "10.0.0.1");

		assertThat(resets.count()).isEqualTo(0);
		Mockito.verify(sender, Mockito.never()).send(Mockito.any());
	}

	@Test
	void resetSetsNewPasswordRetiresSessionsAndConsumesToken() {
		User user = customer("happy", "old-pass-1");
		Instant loginAt = ManualClockConfiguration.NOW_REF.get();
		AuthService.LoginResult before = authService.login(user.getEmail(), "old-pass-1");

		recovery.requestReset(user.getEmail(), "10.0.0.2");
		String rawToken = lastEmailedToken();
		assertThat(resets.findByUserId(user.getId())).isPresent();

		// Time passes (as in reality): the reset lands strictly after
		// the earlier login, so the old token must retire at once.
		ManualClockConfiguration.NOW_REF.set(loginAt.plusSeconds(30));
		recovery.resetPassword(rawToken, "brand-new-9", "10.0.0.3");

		assertThat(credentials.matches(user.getId(), "brand-new-9")).isTrue();
		assertThat(credentials.matches(user.getId(), "old-pass-1")).isFalse();
		assertThatThrownBy(() -> revocations.requireNotRevoked(
				user.getId(), loginAt))
				.isInstanceOf(com.sourabh.smartqueue.auth.JwtInvalidException.class);
		// But a fresh login works.
		assertThat(authService.login(user.getEmail(), "brand-new-9")
				.principal().userId()).isEqualTo(user.getId());
		// The token is single-use.
		assertThatThrownBy(() -> recovery.resetPassword(rawToken, "another-9x", "10.0.0.3"))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(before.principal().userId()).isEqualTo(user.getId());
	}

	@Test
	void resetRejectsGarbageExpiryAndWeakReplacements() {
		User user = customer("reject", "old-pass-1");
		recovery.requestReset(user.getEmail(), "10.0.0.1");
		String rawToken = lastEmailedToken();

		assertThatThrownBy(() -> recovery.resetPassword("not-a-real-token", "brand-new-9", "10.0.0.1"))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> recovery.resetPassword(rawToken, "short-1", "10.0.0.1"))
				.isInstanceOf(InvalidRequestException.class);

		ManualClockConfiguration.NOW_REF.set(NOW.plus(Duration.ofHours(2)));
		assertThatThrownBy(() -> recovery.resetPassword(rawToken, "brand-new-9", "10.0.0.1"))
				.isInstanceOf(InvalidRequestException.class);
		// Expiry left no credential damage behind.
		assertThat(credentials.matches(user.getId(), "old-pass-1")).isTrue();
	}

	@Test
	void changeRequiresCurrentPassword() {
		User user = customer("change", "old-pass-1");
		AuthenticatedPrincipal principal = authService.login(user.getEmail(), "old-pass-1")
				.principal();

		assertThatThrownBy(() -> recovery.changePassword(principal, "wrong-pass-1", "brand-new-9"))
				.isInstanceOf(InvalidRequestException.class);
		recovery.changePassword(principal, "old-pass-1", "brand-new-9");

		assertThat(credentials.matches(user.getId(), "brand-new-9")).isTrue();
		assertThatThrownBy(() -> authService.login(user.getEmail(), "old-pass-1"))
				.isInstanceOf(BadCredentialsException.class);
	}

	@Test
	void revocationHonorsSecondPrecision() {
		User user = customer("precision", "old-pass-1");
		revocations.requireNotRevoked(user.getId(), NOW);
		// The change lands strictly later: earlier tokens retire.
		ManualClockConfiguration.NOW_REF.set(NOW.plusSeconds(5));
		credentials.setPassword(user.getId(), "brand-new-9");
		assertThatThrownBy(() -> revocations.requireNotRevoked(user.getId(), NOW))
				.isInstanceOf(com.sourabh.smartqueue.auth.JwtInvalidException.class);
		// A token from the change's own second stays valid
		// (documented ≤1s edge — JWT iat has second precision).
		revocations.requireNotRevoked(user.getId(), NOW.plusSeconds(5));
	}
}
