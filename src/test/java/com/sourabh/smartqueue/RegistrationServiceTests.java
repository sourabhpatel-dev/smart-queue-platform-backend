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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.auth.AccountVerificationTokenRepository;
import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.EmailNotVerifiedException;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.auth.RateLimiter;
import com.sourabh.smartqueue.auth.application.RegistrationService;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.branch.InvalidBranchRequestException;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.email.EmailDeliveryException;
import com.sourabh.smartqueue.email.EmailMessage;
import com.sourabh.smartqueue.email.EmailSender;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.MembershipStatus;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserEmailConflictException;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Customer/business registration and email verification (see
 * ADR-036 and ADR-037).
 *
 * <p>NOT transactional by design: raw tokens are unrecoverable from
 * storage (only digests persist), so tests capture the emailed link
 * through a mocked sender — and after-commit delivery only fires on
 * real commits. Each test mints unique emails/organizations, so rows
 * never leak across tests. Time rides a manual project Clock
 * (expiry without sleeps).
 */
@Testcontainers
@SpringBootTest
class RegistrationServiceTests {

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

		@Bean
		@Primary
		RateLimiter allowAllRateLimiter() {
			return Mockito.mock(RateLimiter.class);
		}
	}

	@Autowired
	OrganizationRepository organizations;
	@Autowired
	BranchRepository branches;
	@Autowired
	UserRepository users;
	@Autowired
	PasswordCredentialService credentials;
	@Autowired
	AccountVerificationTokenRepository verifications;
	@Autowired
	AuthService authService;
	@Autowired
	RegistrationService registrations;
	@Autowired
	OrganizationMembershipRepository memberships;
	@Autowired
	EmailSender sender;
	@Autowired
	Clock clock;
	@Autowired
	PlatformTransactionManager transactionManager;

	Organization org;

	/**
	 * Reloads a user with associations initialized: repository calls
	 * outside a transaction return detached entities, and lazy
	 * navigation (roles, organization) needs an open session.
	 */
	private User freshUser(String normalizedEmail) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			User user = users.findByEmail(normalizedEmail).orElseThrow();
			user.getRoles().size();
			if (user.getOrganization() != null) {
				user.getOrganization().getId();
			}
			return user;
		});
	}

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		ManualClockConfiguration.NOW_REF.set(NOW);
		Mockito.clearInvocations(sender);
		String tag = UUID.randomUUID().toString().substring(0, 8);
		org = organizations.save(new Organization("Reg Org " + tag));
		assertThat(clock.instant()).isEqualTo(NOW);
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
		ManualClockConfiguration.NOW_REF.set(NOW);
	}

	private String email(String tag) {
		return "t-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

	/** The raw token from the last emailed link (the email is the only carrier). */
	private String lastEmailedToken() {
		ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
		Mockito.verify(sender, Mockito.atLeastOnce()).send(captor.capture());
		String body = captor.getValue().bodyText();
		Matcher matcher = TOKEN_LINK.matcher(body);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	@Test
	void customerRegistersAsPlatformLevelIdentityWithPendingVerification() {
		String address = email("cust");

		RegistrationService.CustomerRegistration result = registrations.registerCustomer(
				"Cleo", address, "s3cure-pass", "10.0.0.1");

		// ADR-040: no organization is chosen, assigned, or returned.
		assertThat(result.organizationId()).isNull();
		assertThat(result.email()).isEqualTo(address.toLowerCase());
		User user = freshUser(address.toLowerCase());
		assertThat(user.getDisplayName()).isEqualTo("Cleo");
		assertThat(user.getRoles()).containsExactly(RoleType.CUSTOMER);
		assertThat(user.getOrganization()).isNull();
		assertThat(credentials.matches(user.getId(), "s3cure-pass")).isTrue();
		assertThat(verifications.findByUserId(user.getId())).isPresent();
		assertThat(lastEmailedToken()).isNotBlank();
	}

	@Test
	void pendingVerificationGatesLoginUntilVerified() {
		String address = email("gate");
		registrations.registerCustomer("Cleo", address, "s3cure-pass", "10.0.0.1");
		User user = freshUser(address.toLowerCase());

		assertThatThrownBy(() -> authService.login(address, "s3cure-pass"))
				.isInstanceOf(EmailNotVerifiedException.class);
		// Wrong password stays generic even while pending (no probing).
		assertThatThrownBy(() -> authService.login(address, "wrong-pass-1"))
				.isInstanceOf(BadCredentialsException.class);

		registrations.verifyEmail(lastEmailedToken(), "10.0.0.2");

		// The platform-level customer authenticates with no organization.
		AuthService.LoginResult result = authService.login(address, "s3cure-pass");
		assertThat(result.principal().userId()).isEqualTo(user.getId());
		assertThat(result.principal().organizationId()).isNull();
		assertThat(result.organizationId()).isNull();
	}

	@Test
	void duplicateEmailFailsHonestly() {
		String address = email("dup");
		registrations.registerCustomer("Cleo", address, "s3cure-pass", "10.0.0.1");

		assertThatThrownBy(() -> registrations.registerCustomer("Other",
				address.toUpperCase(), "other-pass-1", "10.0.0.2"))
				.isInstanceOf(UserEmailConflictException.class);
	}

	@Test
	void weakPasswordsAreRejected() {
		assertThatThrownBy(() -> registrations.registerCustomer("C",
				email("w1"), "short-1", "10.0.0.1"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("between 8 and 72");
		assertThatThrownBy(() -> registrations.registerCustomer("C",
				email("w2"), "allletterspassword", "10.0.0.1"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("letter and one digit");
		assertThatThrownBy(() -> registrations.registerCustomer("C",
				email("w3"), "12345678", "10.0.0.1"))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void publicRegistrationCannotMintOperationalRolesOrMembership() {
		// There is no organizationId/role parameter to smuggle: the only
		// thing public registration ever creates is a CUSTOMER with no
		// organization and no membership.
		String address = email("priv");
		RegistrationService.CustomerRegistration result = registrations.registerCustomer(
				"Priv", address, "priv-pass-1", "10.0.0.1");
		User user = freshUser(address.toLowerCase());

		assertThat(result.organizationId()).isNull();
		assertThat(user.getRoles()).containsExactly(RoleType.CUSTOMER);
		assertThat(user.getRoles()).doesNotContain(
				RoleType.STAFF, RoleType.MANAGER, RoleType.ADMIN);
		assertThat(memberships.findByUserIdAndStatus(
				user.getId(), com.sourabh.smartqueue.membership.MembershipStatus.ACTIVE))
				.isEmpty();
	}

	@Test
	void businessApplicationIsCreatedPendingWithInactiveMembership() {
		String address = email("owner");

		RegistrationService.BusinessRegistration result = registrations.registerBusiness(
				"Harbor Clinic " + UUID.randomUUID(), "Mira", address, "owner-pass-1",
				"Downtown", "Bhopal", "India", "Asia/Kolkata", "10.0.0.3");

		// ADR-041: a business submission is an application, never an
		// instantly usable organization.
		assertThat(result.branchId()).isNotNull();
		Organization application = organizations.findById(result.organizationId()).orElseThrow();
		assertThat(application.getStatus())
				.isEqualTo(com.sourabh.smartqueue.organization.OrganizationStatus.PENDING_APPROVAL);
		User owner = freshUser(address.toLowerCase());
		assertThat(owner.getRoles()).containsExactly(RoleType.ADMIN);
		assertThat(owner.getOrganization().getId()).isEqualTo(result.organizationId());
		// The owner's ADMIN membership is INACTIVE until platform review
		// approves: the applicant administers nothing yet.
		assertThat(memberships.findByUserIdAndOrganizationId(
				owner.getId(), result.organizationId()))
				.get()
				.satisfies(m -> {
					assertThat(m.getRole()).isEqualTo(MembershipRole.ADMIN);
					assertThat(m.getStatus()).isEqualTo(MembershipStatus.INACTIVE);
				});
		assertThat(branches.findById(result.branchId())).isPresent();
		assertThat(credentials.matches(owner.getId(), "owner-pass-1")).isTrue();
		assertThat(verifications.findByUserId(owner.getId())).isPresent();
	}

	@Test
	void businessRejectsBadTimezoneAndDuplicateOwnerAtomically() {
		// "Not/AZone" is not parseable at all (legacy short ids like
		// IST still parse via ZoneId.of — same leniency as branch
		// management, deliberately unchanged here). The branch
		// domain's own 400 carries the message, like everywhere else.
		assertThatThrownBy(() -> registrations.registerBusiness("H", "M", email("b1"),
				"owner-pass-1", "B", "C", "IN", "Not/AZone", "10.0.0.1"))
				.isInstanceOf(InvalidBranchRequestException.class)
				.hasMessageContaining("Invalid time zone");
		String address = email("b2");
		registrations.registerBusiness("H1 " + UUID.randomUUID(), "M", address,
				"owner-pass-1", "B", "C", "IN", "Asia/Kolkata", "10.0.0.1");
		long orgsBefore = organizations.count();
		assertThatThrownBy(() -> registrations.registerBusiness("H2 " + UUID.randomUUID(),
				"M", address, "owner-pass-1", "B", "C", "IN", "Asia/Kolkata", "10.0.0.1"))
				.isInstanceOf(UserEmailConflictException.class);
		// Atomicity: the duplicate-owner attempt left no organization behind.
		assertThat(organizations.count()).isEqualTo(orgsBefore);
	}

	@Test
	void verificationRejectsGarbageReuseAndExpiry() {
		String address = email("vfy");
		registrations.registerCustomer("Cleo", address, "s3cure-pass", "10.0.0.1");
		String raw = lastEmailedToken();

		assertThatThrownBy(() -> registrations.verifyEmail("not-a-real-token", "10.0.0.1"))
				.isInstanceOf(InvalidRequestException.class);
		registrations.verifyEmail(raw, "10.0.0.1");
		assertThatThrownBy(() -> registrations.verifyEmail(raw, "10.0.0.1"))
				.isInstanceOf(InvalidRequestException.class);

		String address2 = email("vfy2");
		registrations.registerCustomer("Ravi", address2, "s3cure-pass", "10.0.0.1");
		// Latest email on the mock belongs to this registration.
		String raviRaw = lastEmailedToken();
		ManualClockConfiguration.NOW_REF.set(NOW.plus(Duration.ofHours(25)));
		assertThatThrownBy(() -> registrations.verifyEmail(raviRaw, "10.0.0.1"))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void resendIsGenericAndOnlyReplacesLiveTokens() {
		// Unknown email: identical success, no row, no email.
		registrations.resendVerification("ghost-" + UUID.randomUUID() + "@example.com", "10.0.0.9");
		Mockito.verify(sender, Mockito.never()).send(Mockito.any());

		String address = email("rsnd");
		registrations.registerCustomer("Cleo", address, "s3cure-pass", "10.0.0.1");
		User user = freshUser(address.toLowerCase());
		String first = lastEmailedToken();
		Mockito.clearInvocations(sender);
		registrations.resendVerification(address, "10.0.0.1");
		String second = lastEmailedToken();
		assertThat(second).isNotEqualTo(first);
		// The replaced token is dead.
		assertThatThrownBy(() -> registrations.verifyEmail(first, "10.0.0.1"))
				.isInstanceOf(InvalidRequestException.class);
		registrations.verifyEmail(second, "10.0.0.1");

		// Verified accounts get nothing new (and stay verified).
		Mockito.clearInvocations(sender);
		registrations.resendVerification(address, "10.0.0.1");
		Mockito.verify(sender, Mockito.never()).send(Mockito.any());
		assertThat(authService.login(address, "s3cure-pass").principal().userId())
				.isEqualTo(user.getId());
	}

	@Test
	void registrationSurvivesEmailDeliveryFailure() {
		// Transport trouble degrades to logs, never to partial accounts:
		// the user, credential, and live token all commit, login stays
		// gated, and the response claims pending verification — never
		// delivered email. The resend path recovers once the relay is back.
		String address = email("smtpfail");
		Mockito.doThrow(new EmailDeliveryException("relay down", new RuntimeException("refused")))
				.when(sender).send(Mockito.any());
		try {
			RegistrationService.CustomerRegistration result = registrations.registerCustomer(
					"Cleo", address, "s3cure-pass", "10.0.0.1");
			assertThat(result.userId()).isNotNull();
		} finally {
			Mockito.reset(sender);
		}

		User user = freshUser(address.toLowerCase());
		assertThat(verifications.findByUserId(user.getId())).isPresent();
		assertThatThrownBy(() -> authService.login(address, "s3cure-pass"))
				.isInstanceOf(EmailNotVerifiedException.class);

		registrations.resendVerification(address, "10.0.0.1");
		registrations.verifyEmail(lastEmailedToken(), "10.0.0.1");
		assertThat(authService.login(address, "s3cure-pass").principal().userId())
				.isEqualTo(user.getId());
	}

	@Test
	void verificationTokenVerifiesOnlyItsOwnAccount() {
		String first = email("acct-a");
		registrations.registerCustomer("Ann", first, "s3cure-pass", "10.0.0.1");
		String tokenA = lastEmailedToken();
		String second = email("acct-b");
		registrations.registerCustomer("Ben", second, "s3cure-pass", "10.0.0.1");
		String tokenB = lastEmailedToken();
		assertThat(tokenA).isNotEqualTo(tokenB);

		// A's token verifies A only: B stays gated until B's own token
		// is consumed. Tokens are bound to their account by storage —
		// the request carries no account identity to forge.
		registrations.verifyEmail(tokenA, "10.0.0.1");
		assertThat(authService.login(first, "s3cure-pass").principal().userId())
				.isEqualTo(freshUser(first.toLowerCase()).getId());
		assertThatThrownBy(() -> authService.login(second, "s3cure-pass"))
				.isInstanceOf(EmailNotVerifiedException.class);

		registrations.verifyEmail(tokenB, "10.0.0.1");
		assertThat(authService.login(second, "s3cure-pass").principal().userId())
				.isEqualTo(freshUser(second.toLowerCase()).getId());
	}
}

