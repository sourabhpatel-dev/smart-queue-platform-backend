package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.appointment.AppointmentBookingService;
import com.sourabh.smartqueue.appointment.InvalidAppointmentRequestException;
import com.sourabh.smartqueue.appointment.api.CreateAppointmentRequest;
import com.sourabh.smartqueue.appointment.application.AppointmentService;
import com.sourabh.smartqueue.auth.AccountVerificationToken;
import com.sourabh.smartqueue.auth.AccountVerificationTokenRepository;
import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.EmailNotVerifiedException;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.auth.RateLimiter;
import com.sourabh.smartqueue.auth.application.RegistrationService;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.discovery.application.DiscoveryService;
import com.sourabh.smartqueue.email.EmailMessage;
import com.sourabh.smartqueue.email.EmailSender;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.MembershipStatus;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.organization.OrganizationStatus;
import com.sourabh.smartqueue.organization.application.OrganizationApplicationService;
import com.sourabh.smartqueue.platform.OrganizationTransitionException;
import com.sourabh.smartqueue.platform.application.OrganizationReviewService;
import com.sourabh.smartqueue.queue.InvalidQueueRequestException;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueJoinService;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.api.JoinQueueRequest;
import com.sourabh.smartqueue.queue.application.QueueService;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserEmailConflictException;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.workinghour.WorkingHour;
import com.sourabh.smartqueue.workinghour.WorkingHourRepository;

/**
 * Organization onboarding & approval end to end (Phase 4R.4, ADR-041).
 *
 * <p>Business applications start {@code PENDING_APPROVAL} with an
 * {@code INACTIVE} owner membership; email verification never activates;
 * only a configured, verified platform administrator decides. Non-
 * transactional like {@code RegistrationServiceTests} (raw tokens travel
 * by email only), unique data per test, manual Clock (no sleeps).
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = "app.platform.admin-emails=platform-admin@example.com, platform-unverified@example.com")
class OrganizationOnboardingTests {

	static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	static final Pattern TOKEN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");
	static final String ADMIN_EMAIL = "platform-admin@example.com";
	static final String UNVERIFIED_ADMIN_EMAIL = "platform-unverified@example.com";

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
	ServiceRepository services;
	@Autowired
	WorkingHourRepository workingHours;
	@Autowired
	QueueRepository queues;
	@Autowired
	UserRepository users;
	@Autowired
	PasswordCredentialService credentials;
	@Autowired
	AccountVerificationTokenRepository verifications;
	@Autowired
	OrganizationMembershipRepository memberships;
	@Autowired
	RegistrationService registrations;
	@Autowired
	AuthService authService;
	@Autowired
	OrganizationApplicationService ownerStatus;
	@Autowired
	OrganizationReviewService review;
	@Autowired
	DiscoveryService discovery;
	@Autowired
	AppointmentService appointmentService;
	@Autowired
	AppointmentBookingService booking;
	@Autowired
	QueueService queueService;
	@Autowired
	QueueJoinService joinService;
	@Autowired
	EmailSender sender;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	PlatformTransactionManager transactionManager;

	private static volatile boolean adminSeeded;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		ManualClockConfiguration.NOW_REF.set(NOW);
		Mockito.clearInvocations(sender);
		seedAdminOnce();
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
		ManualClockConfiguration.NOW_REF.set(NOW);
	}

	private synchronized void seedAdminOnce() {
		if (adminSeeded) {
			return;
		}
		new TransactionTemplate(transactionManager).execute(status -> {
			User admin = new User(ADMIN_EMAIL, "Platform Admin");
			admin.grantRole(RoleType.CUSTOMER);
			users.save(admin);
			credentials.setPassword(admin.getId(), "admin-pass-1");
			AccountVerificationToken token = new AccountVerificationToken(admin,
					"seed-admin-" + UUID.randomUUID(), NOW.plus(Duration.ofDays(1)));
			token.markUsed(NOW);
			verifications.save(token);
			return null;
		});
		adminSeeded = true;
	}

	private String email(String tag) {
		return "onb-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

	private String lastEmailedToken() {
		ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
		Mockito.verify(sender, Mockito.atLeastOnce()).send(captor.capture());
		Matcher matcher = TOKEN_LINK.matcher(captor.getValue().bodyText());
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

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

	private Organization freshOrg(UUID organizationId) {
		return new TransactionTemplate(transactionManager).execute(status ->
				organizations.findById(organizationId).orElseThrow());
	}

	private AuthenticatedPrincipal adminPrincipal() {
		User admin = freshUser(ADMIN_EMAIL);
		return new AuthenticatedPrincipal(admin.getId(), null, ADMIN_EMAIL,
				Set.of(RoleType.CUSTOMER, RoleType.PLATFORM_ADMIN));
	}

	private AuthenticatedPrincipal customerPrincipal(User customer) {
		return new AuthenticatedPrincipal(customer.getId(), null, customer.getEmail(),
				Set.copyOf(customer.getRoles()));
	}

	private AuthenticatedPrincipal operationalPrincipal(User user, UUID organizationId) {
		return new AuthenticatedPrincipal(user.getId(), organizationId, user.getEmail(),
				Set.copyOf(user.getRoles()));
	}

	private RegistrationService.BusinessRegistration apply(String tag) {
		return registrations.registerBusiness(
				"Onboarding Clinic " + tag + " " + UUID.randomUUID(), "Owner " + tag,
				email(tag), "owner-pass-1", "Main", "Bhopal", "India", "Asia/Kolkata",
				"10.0.0.1");
	}

	private void verifyOwner(String ownerEmail) {
		registrations.verifyEmail(lastEmailedToken(), "10.0.0.2");
		assertThat(freshUser(ownerEmail.toLowerCase()).getId()).isNotNull();
	}

	private void verifyOwnerWithToken(String ownerEmail, String rawToken) {
		registrations.verifyEmail(rawToken, "10.0.0.2");
		assertThat(freshUser(ownerEmail.toLowerCase()).getId()).isNotNull();
	}

	private Branch fullDayBranch(Organization org, String name) {
		Branch branch = branches.save(new Branch(org, name + " " + UUID.randomUUID(),
				"Bhopal", "India", KOLKATA));
		for (DayOfWeek day : DayOfWeek.values()) {
			workingHours.save(new WorkingHour(branch, day,
					LocalTime.of(0, 0), LocalTime.of(23, 59)));
		}
		return branch;
	}

	// -- Business application ------------------------------------------

	@Test
	void validApplicationIsCreatedPendingWithInactiveMembership() {
		RegistrationService.BusinessRegistration result = apply("valid");

		Organization org = freshOrg(result.organizationId());
		assertThat(org.getStatus()).isEqualTo(OrganizationStatus.PENDING_APPROVAL);
		assertThat(org.getRejectionReason()).isNull();
		assertThat(org.getDecidedAt()).isNull();
		assertThat(org.getDecidedByUserId()).isNull();
		OrganizationMembership membership = memberships
				.findByUserIdAndOrganizationId(result.userId(), result.organizationId())
				.orElseThrow();
		assertThat(membership.getRole()).isEqualTo(MembershipRole.ADMIN);
		assertThat(membership.getStatus()).isEqualTo(MembershipStatus.INACTIVE);
	}

	@Test
	void invalidApplicationInputIsRejected() {
		assertThatThrownBy(() -> registrations.registerBusiness("  ", "M", email("i1"),
				"owner-pass-1", "B", "C", "IN", "Asia/Kolkata", "10.0.0.1"))
				.isInstanceOf(com.sourabh.smartqueue.common.api.InvalidRequestException.class);
		assertThatThrownBy(() -> registrations.registerBusiness("H", "M", "not-an-email",
				"owner-pass-1", "B", "C", "IN", "Asia/Kolkata", "10.0.0.1"))
				.isInstanceOf(com.sourabh.smartqueue.common.api.InvalidRequestException.class);
		assertThatThrownBy(() -> registrations.registerBusiness("H", "M", email("i3"),
				"short-1", "B", "C", "IN", "Asia/Kolkata", "10.0.0.1"))
				.isInstanceOf(com.sourabh.smartqueue.common.api.InvalidRequestException.class);
		assertThatThrownBy(() -> registrations.registerBusiness("H", "M", email("i4"),
				"owner-pass-1", "B", "C", "IN", "Not/AZone", "10.0.0.1"))
				.isInstanceOf(com.sourabh.smartqueue.branch.InvalidBranchRequestException.class);
	}

	@Test
	void duplicateSubmissionIsSafeAndKeepsFirstApplicationPending() {
		String address = email("dup");
		RegistrationService.BusinessRegistration first = registrations.registerBusiness(
				"H1 " + UUID.randomUUID(), "M", address, "owner-pass-1",
				"B", "C", "IN", "Asia/Kolkata", "10.0.0.1");
		long orgsBefore = organizations.count();
		assertThatThrownBy(() -> registrations.registerBusiness("H2 " + UUID.randomUUID(),
				"M", address, "owner-pass-1", "B", "C", "IN", "Asia/Kolkata", "10.0.0.1"))
				.isInstanceOf(UserEmailConflictException.class);
		assertThat(organizations.count()).isEqualTo(orgsBefore);
		assertThat(freshOrg(first.organizationId()).getStatus())
				.isEqualTo(OrganizationStatus.PENDING_APPROVAL);
	}

	@Test
	void personalEmailProvidersAreAccepted() {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		for (String domain : List.of("gmail.com", "outlook.com", "yahoo.co.in")) {
			String address = "owner-" + tag + "-" + domain.replace('.', '-') + "@" + domain;
			RegistrationService.BusinessRegistration result = registrations.registerBusiness(
					"Local Shop " + UUID.randomUUID(), "Proprietor", address, "owner-pass-1",
					"Counter", "Indore", "India", "Asia/Kolkata", "10.0.0.1");
			assertThat(freshOrg(result.organizationId()).getStatus())
					.isEqualTo(OrganizationStatus.PENDING_APPROVAL);
		}
	}

	// -- Email verification --------------------------------------------

	@Test
	void emailVerificationDoesNotActivateTheOrganization() {
		RegistrationService.BusinessRegistration result = apply("verify-no-activate");
		User owner = freshUser(registrationsEmail(result));
		registrations.verifyEmail(lastEmailedToken(), "10.0.0.2");

		assertThat(freshOrg(result.organizationId()).getStatus())
				.isEqualTo(OrganizationStatus.PENDING_APPROVAL);
		assertThat(memberships.findByUserIdAndOrganizationId(
				owner.getId(), result.organizationId()).orElseThrow().getStatus())
				.isEqualTo(MembershipStatus.INACTIVE);
		// The owner can sign in (verified) but resolves no organization.
		AuthService.LoginResult login =
				authService.login(owner.getEmail(), "owner-pass-1");
		assertThat(login.organizationId()).isNull();
	}

	@Test
	void verificationTokensAreBoundToTheirOwnApplication() {
		RegistrationService.BusinessRegistration first = apply("bind-a");
		String tokenA = lastEmailedToken();
		RegistrationService.BusinessRegistration second = apply("bind-b");
		String tokenB = lastEmailedToken();
		assertThat(tokenA).isNotEqualTo(tokenB);

		registrations.verifyEmail(tokenA, "10.0.0.1");
		User ownerB = freshUser(registrationsEmail(second));
		assertThatThrownBy(() -> authService.login(ownerB.getEmail(), "owner-pass-1"))
				.isInstanceOf(EmailNotVerifiedException.class);
		assertThatThrownBy(() -> registrations.verifyEmail(tokenA, "10.0.0.1"))
				.isInstanceOf(com.sourabh.smartqueue.common.api.InvalidRequestException.class);

		registrations.verifyEmail(tokenB, "10.0.0.1");
		assertThat(authService.login(ownerB.getEmail(), "owner-pass-1")
				.principal().userId()).isEqualTo(ownerB.getId());
		assertThat(first.organizationId()).isNotEqualTo(second.organizationId());
	}

	@Test
	void expiredAndGarbageTokensAreRejected() {
		apply("expiry");
		String raw = lastEmailedToken();
		assertThatThrownBy(() -> registrations.verifyEmail("not-a-real-token", "10.0.0.1"))
				.isInstanceOf(com.sourabh.smartqueue.common.api.InvalidRequestException.class);
		ManualClockConfiguration.NOW_REF.set(NOW.plus(Duration.ofHours(25)));
		assertThatThrownBy(() -> registrations.verifyEmail(raw, "10.0.0.1"))
				.isInstanceOf(com.sourabh.smartqueue.common.api.InvalidRequestException.class);
	}

	private String registrationsEmail(RegistrationService.BusinessRegistration result) {
		return new TransactionTemplate(transactionManager).execute(status ->
				users.findById(result.userId()).orElseThrow().getEmail());
	}

	// -- Authorization ---------------------------------------------------

	@Test
	void organizationAdminCannotReviewApplications() {
		Organization org = organizations.save(new Organization("Foreign Org " + UUID.randomUUID()));
		User admin = new TransactionTemplate(transactionManager).execute(status -> {
			User user = new User(org, "foreign-admin-" + UUID.randomUUID() + "@example.com",
					"Foreign Admin");
			user.grantRole(RoleType.ADMIN);
			users.save(user);
			memberships.save(new OrganizationMembership(user, org, MembershipRole.ADMIN));
			return user;
		});
		AuthenticatedPrincipal foreignAdmin =
				operationalPrincipal(freshUser(admin.getEmail()), org.getId());

		assertThatThrownBy(() -> review.listApplications(foreignAdmin, null))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> review.approve(foreignAdmin, UUID.randomUUID()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> review.reject(foreignAdmin, UUID.randomUUID(), "no"))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void customersAndStaffCannotReviewApplications() {
		RegistrationService.BusinessRegistration pending = apply("deny");
		// A platform-level customer with no application at all.
		AuthenticatedPrincipal customer = new AuthenticatedPrincipal(UUID.randomUUID(), null,
				"ghost-" + UUID.randomUUID() + "@example.com", Set.of(RoleType.CUSTOMER));
		assertThatThrownBy(() -> review.listApplications(customer, null))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> review.approve(customer, pending.organizationId()))
				.isInstanceOf(AccessDeniedException.class);

		Organization org = organizations.save(new Organization("Staff Org " + UUID.randomUUID()));
		User staff = new TransactionTemplate(transactionManager).execute(status -> {
			User user = new User(org, "staffer-" + UUID.randomUUID() + "@example.com", "Staff");
			user.grantRole(RoleType.STAFF);
			users.save(user);
			memberships.save(new OrganizationMembership(user, org, MembershipRole.STAFF));
			credentials.setPassword(user.getId(), "staff-pass-1");
			return user;
		});
		AuthenticatedPrincipal staffer =
				operationalPrincipal(freshUser(staff.getEmail()), org.getId());
		assertThatThrownBy(() -> review.listApplications(staffer, null))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void ordinaryVerifiedUserCannotReview() {
		String address = email("ordinary");
		registrations.registerCustomer("Ordinary", address, "ordinary-1", "10.0.0.1");
		registrations.verifyEmail(lastEmailedToken(), "10.0.0.1");
		User user = freshUser(address.toLowerCase());
		AuthenticatedPrincipal ordinary = customerPrincipal(user);

		assertThatThrownBy(() -> review.listApplications(ordinary, null))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> review.approve(ordinary, UUID.randomUUID()))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void configuredButUnverifiedAccountCannotReviewEvenWithAPlatformRoleClaim() {
		String address = UNVERIFIED_ADMIN_EMAIL;
		try {
			registrations.registerCustomer("Unverified Admin", address, "unverified-1", "10.0.0.1");
		} catch (UserEmailConflictException alreadySeeded) {
			// Re-runs in one JVM share the container: the account persists.
		}
		User user = freshUser(address.toLowerCase());
		// Even presenting the platform role (as a stale/forged token
		// would) fails: the policy checks live verification state.
		AuthenticatedPrincipal forged = new AuthenticatedPrincipal(user.getId(), null,
				address.toLowerCase(), Set.of(RoleType.CUSTOMER, RoleType.PLATFORM_ADMIN));
		assertThatThrownBy(() -> review.listApplications(forged, null))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> authService.login(address, "unverified-1"))
				.isInstanceOf(EmailNotVerifiedException.class);
	}

	@Test
	void businessOwnerCannotSelfAssignPlatformAuthority() {
		RegistrationService.BusinessRegistration pending = apply("selfassign");
		User owner = freshUser(registrationsEmail(pending));
		registrations.verifyEmail(lastEmailedToken(), "10.0.0.2");
		AuthenticatedPrincipal applicant =
				new AuthenticatedPrincipal(owner.getId(), null, owner.getEmail(),
						Set.copyOf(owner.getRoles()));

		assertThatThrownBy(() -> review.listApplications(applicant, null))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> review.approve(applicant, pending.organizationId()))
				.isInstanceOf(AccessDeniedException.class);

		// And approval does not change that: org ADMIN stays org-scoped.
		review.approve(adminPrincipal(), pending.organizationId());
		AuthService.LoginResult login =
				authService.login(owner.getEmail(), "owner-pass-1");
		assertThat(login.organizationId()).isEqualTo(pending.organizationId());
		assertThat(login.principal().roles()).doesNotContain(RoleType.PLATFORM_ADMIN);
		AuthenticatedPrincipal orgAdmin = new AuthenticatedPrincipal(owner.getId(),
				pending.organizationId(), owner.getEmail(), Set.copyOf(owner.getRoles()));
		assertThatThrownBy(() -> review.listApplications(orgAdmin, null))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void configuredAdminLoginCarriesPlatformRoleWithNoOrganization() {
		AuthService.LoginResult login = authService.login(ADMIN_EMAIL, "admin-pass-1");
		assertThat(login.principal().roles()).contains(RoleType.PLATFORM_ADMIN);
		assertThat(login.organizationId()).isNull();
	}

	// -- Approval / rejection --------------------------------------------

	@Test
	void approvalRequiresVerificationAndActivatesBothRows() {
		RegistrationService.BusinessRegistration pending = apply("approve-flow");
		assertThatThrownBy(() -> review.approve(adminPrincipal(), pending.organizationId()))
				.isInstanceOf(OrganizationTransitionException.class);
		assertThat(freshOrg(pending.organizationId()).getStatus())
				.isEqualTo(OrganizationStatus.PENDING_APPROVAL);

		verifyOwner(registrationsEmail(pending));
		var decided = review.approve(adminPrincipal(), pending.organizationId());
		assertThat(decided.status()).isEqualTo("ACTIVE");

		Organization org = freshOrg(pending.organizationId());
		assertThat(org.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);
		assertThat(org.getRejectionReason()).isNull();
		assertThat(org.getDecidedAt()).isEqualTo(NOW);
		assertThat(org.getDecidedByUserId())
				.isEqualTo(freshUser(ADMIN_EMAIL).getId());
		assertThat(memberships.findByUserIdAndOrganizationId(
				pending.userId(), pending.organizationId()).orElseThrow().getStatus())
				.isEqualTo(MembershipStatus.ACTIVE);
		// Exactly one membership row: approval flips in place, never inserts.
		assertThat(memberships.findByOrganizationIdAndStatus(
				pending.organizationId(), MembershipStatus.ACTIVE)).hasSize(1);
	}

	@Test
	void rejectionPersistsOwnerVisibleReasonAndKeepsMembershipInactive() {
		RegistrationService.BusinessRegistration pending = apply("reject-flow");
		verifyOwner(registrationsEmail(pending));

		var decided = review.reject(adminPrincipal(), pending.organizationId(),
				"Photos of the premises are unclear — reply with clearer ones.");
		assertThat(decided.status()).isEqualTo("REJECTED");
		assertThat(decided.rejectionReason()).contains("unclear");

		Organization org = freshOrg(pending.organizationId());
		assertThat(org.getStatus()).isEqualTo(OrganizationStatus.REJECTED);
		assertThat(org.getDecidedAt()).isEqualTo(NOW);
		assertThat(memberships.findByUserIdAndOrganizationId(
				pending.userId(), pending.organizationId()).orElseThrow().getStatus())
				.isEqualTo(MembershipStatus.INACTIVE);
	}

	@Test
	void invalidTransitionsFailSafely() {
		RegistrationService.BusinessRegistration pending = apply("transitions");
		verifyOwner(registrationsEmail(pending));
		review.approve(adminPrincipal(), pending.organizationId());

		assertThatThrownBy(
				() -> review.approve(adminPrincipal(), pending.organizationId()))
				.isInstanceOf(OrganizationTransitionException.class);
		assertThatThrownBy(() -> review.reject(adminPrincipal(), pending.organizationId(), "x"))
				.isInstanceOf(OrganizationTransitionException.class);

		RegistrationService.BusinessRegistration second = apply("transitions-2");
		verifyOwner(registrationsEmail(second));
		review.reject(adminPrincipal(), second.organizationId(), "Not eligible");
		assertThatThrownBy(
				() -> review.approve(adminPrincipal(), second.organizationId()))
				.isInstanceOf(OrganizationTransitionException.class);
		assertThatThrownBy(
				() -> review.reject(adminPrincipal(), second.organizationId(), "Again"))
				.isInstanceOf(OrganizationTransitionException.class);
		assertThat(freshOrg(second.organizationId()).getStatus())
				.isEqualTo(OrganizationStatus.REJECTED);

		RegistrationService.BusinessRegistration third = apply("transitions-3");
		verifyOwner(registrationsEmail(third));
		assertThatThrownBy(() -> review.reject(adminPrincipal(), third.organizationId(), "  "))
				.isInstanceOf(OrganizationTransitionException.class);
		assertThatThrownBy(() -> review.reject(adminPrincipal(), third.organizationId(),
				"x".repeat(501))).isInstanceOf(OrganizationTransitionException.class);
		assertThat(freshOrg(third.organizationId()).getStatus())
				.isEqualTo(OrganizationStatus.PENDING_APPROVAL);
	}

	@Test
	void concurrentApprovalsDoNotForkState() throws Exception {
		RegistrationService.BusinessRegistration pending = apply("race");
		verifyOwner(registrationsEmail(pending));
		AuthenticatedPrincipal admin = adminPrincipal();

		CountDownLatch gate = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = pool.submit(() -> {
				gate.await();
				return review.approve(admin, pending.organizationId());
			});
			Future<?> second = pool.submit(() -> {
				gate.await();
				return review.approve(admin, pending.organizationId());
			});
			gate.countDown();
			int successes = 0;
			int conflicts = 0;
			for (Future<?> future : List.of(first, second)) {
				try {
					future.get();
					successes++;
				} catch (java.util.concurrent.ExecutionException e) {
					assertThat(e.getCause()).isInstanceOf(OrganizationTransitionException.class);
					conflicts++;
				}
			}
			assertThat(successes).isEqualTo(1);
			assertThat(conflicts).isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
		assertThat(freshOrg(pending.organizationId()).getStatus())
				.isEqualTo(OrganizationStatus.ACTIVE);
		assertThat(memberships.findByOrganizationIdAndStatus(
				pending.organizationId(), MembershipStatus.ACTIVE)).hasSize(1);
		assertThat(memberships.findByOrganizationIdAndStatus(
				pending.organizationId(), MembershipStatus.INACTIVE)).isEmpty();
	}

	// -- Owner status ------------------------------------------------------

	@Test
	void ownerSeesVerificationPendingAndRejectionFeedback() {
		RegistrationService.BusinessRegistration pending = apply("mine");
		User owner = freshUser(registrationsEmail(pending));
		AuthenticatedPrincipal applicant = new AuthenticatedPrincipal(owner.getId(), null,
				owner.getEmail(), Set.copyOf(owner.getRoles()));

		var beforeVerify = ownerStatus.myApplication(applicant);
		assertThat(beforeVerify.status()).isEqualTo("PENDING_APPROVAL");
		assertThat(beforeVerify.emailVerified()).isFalse();
		assertThat(beforeVerify.organizationId()).isEqualTo(pending.organizationId());

		registrations.verifyEmail(lastEmailedToken(), "10.0.0.2");
		assertThat(ownerStatus.myApplication(applicant).emailVerified()).isTrue();

		review.reject(adminPrincipal(), pending.organizationId(), "Missing trade license copy.");
		var rejected = ownerStatus.myApplication(applicant);
		assertThat(rejected.status()).isEqualTo("REJECTED");
		assertThat(rejected.rejectionReason()).contains("trade license");
	}

	@Test
	void callersWithoutAnApplicationGetScopeSafeNotFound() {
		String address = email("noapp");
		registrations.registerCustomer("No App", address, "noapp-pass-1", "10.0.0.1");
		User user = freshUser(address.toLowerCase());
		assertThatThrownBy(() -> ownerStatus.myApplication(customerPrincipal(user)))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	// -- Visibility and booking --------------------------------------------

	@Test
	void pendingAndRejectedAreUndiscoverableAndUnusableUntilApproved() {
		RegistrationService.BusinessRegistration pending = apply("visibility");
		String ownerToken = lastEmailedToken();
		String orgName = freshOrg(pending.organizationId()).getName();
		UUID branchId = pending.branchId();

		assertThat(discovery.organizations()).noneMatch(o -> o.id().equals(pending.organizationId()));
		assertThatThrownBy(() -> discovery.branches(pending.organizationId()))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> discovery.services(branchId))
				.isInstanceOf(ResourceNotFoundException.class);

		String customerEmail = email("booker");
		registrations.registerCustomer("Booker", customerEmail, "booker-pass-1", "10.0.0.1");
		registrations.verifyEmail(lastEmailedToken(), "10.0.0.1");
		User customer = freshUser(customerEmail.toLowerCase());
		AuthenticatedPrincipal booker = customerPrincipal(customer);

		// Domain backstops refuse directly, regardless of UI hiding.
		com.sourabh.smartqueue.service.Service svc = new TransactionTemplate(transactionManager)
				.execute(status -> {
					Branch branch = branches.findById(branchId).orElseThrow();
					com.sourabh.smartqueue.service.Service service = services.save(
							new com.sourabh.smartqueue.service.Service(branch, "Checkup",
									Duration.ofMinutes(30)));
					queues.save(new Queue(freshOrg(pending.organizationId()), branch, service));
					return service;
				});
		UUID queueId = new TransactionTemplate(transactionManager).execute(status ->
				queues.findByBranchIdAndServiceId(branchId, svc.getId()).orElseThrow().getId());
		assertThatThrownBy(() -> booking.book(customer.getId(), svc.getId(),
				NOW.plus(Duration.ofHours(2))))
				.isInstanceOf(InvalidAppointmentRequestException.class)
				.hasMessageContaining("not active");
		assertThatThrownBy(() -> joinService.join(queueId, customer.getId(), QueuePriority.NORMAL))
				.isInstanceOf(InvalidQueueRequestException.class)
				.hasMessageContaining("not active");
		assertThatThrownBy(() -> appointmentService.book(booker, branchId,
				new CreateAppointmentRequest(svc.getId(), NOW.plus(Duration.ofHours(2)))))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> queueService.join(booker, branchId, queueId,
				new JoinQueueRequest(null)))
				.isInstanceOf(ResourceNotFoundException.class);

		verifyOwnerWithToken(registrationsEmail(pending), ownerToken);
		review.reject(adminPrincipal(), pending.organizationId(), "Cannot verify premises.");
		assertThat(discovery.organizations()).noneMatch(o -> o.id().equals(pending.organizationId()));
		assertThatThrownBy(() -> booking.book(customer.getId(), svc.getId(),
				NOW.plus(Duration.ofHours(2))))
				.isInstanceOf(InvalidAppointmentRequestException.class);

		assertThat(orgName).isNotBlank();
	}

	@Test
	void approvedOrganizationBecomesDiscoverableAndUsable() {
		RegistrationService.BusinessRegistration pending = apply("usable");
		verifyOwner(registrationsEmail(pending));
		review.approve(adminPrincipal(), pending.organizationId());

		assertThat(discovery.organizations())
				.anyMatch(o -> o.id().equals(pending.organizationId()));
		assertThat(discovery.branches(pending.organizationId()))
				.anyMatch(b -> b.id().equals(pending.branchId()));

		String customerEmail = email("booker2");
		registrations.registerCustomer("Booker Two", customerEmail, "booker-pass-1", "10.0.0.1");
		registrations.verifyEmail(lastEmailedToken(), "10.0.0.1");
		User customer = freshUser(customerEmail.toLowerCase());
		AuthenticatedPrincipal booker = customerPrincipal(customer);

		UUID serviceId = new TransactionTemplate(transactionManager).execute(status -> {
			Branch branch = branches.findById(pending.branchId()).orElseThrow();
			for (DayOfWeek day : DayOfWeek.values()) {
				workingHours.save(new WorkingHour(branch, day,
						LocalTime.of(0, 0), LocalTime.of(23, 59)));
			}
			com.sourabh.smartqueue.service.Service service = services.save(
					new com.sourabh.smartqueue.service.Service(branch, "Consultation",
							Duration.ofMinutes(30)));
			queues.save(new Queue(freshOrg(pending.organizationId()), branch, service));
			return service.getId();
		});
		assertThat(discovery.services(pending.branchId()))
				.anyMatch(s -> s.id().equals(serviceId));
		var booked = appointmentService.book(booker, pending.branchId(),
				new CreateAppointmentRequest(serviceId, NOW.plus(Duration.ofHours(2))));
		assertThat(booked).isNotNull();
	}

	@Test
	void legacyActiveOrganizationsStayDiscoverableAndBookable() {
		Organization legacy = organizations.save(new Organization("Legacy Clinic " + UUID.randomUUID()));
		Branch branch = fullDayBranch(legacy, "Legacy Branch");
		com.sourabh.smartqueue.service.Service service = services.save(
				new com.sourabh.smartqueue.service.Service(branch, "General",
						Duration.ofMinutes(30)));

		assertThat(freshOrg(legacy.getId()).getStatus()).isEqualTo(OrganizationStatus.ACTIVE);
		assertThat(discovery.organizations()).anyMatch(o -> o.id().equals(legacy.getId()));

		String customerEmail = email("legacy-booker");
		registrations.registerCustomer("Legacy Booker", customerEmail, "booker-pass-1", "10.0.0.1");
		registrations.verifyEmail(lastEmailedToken(), "10.0.0.1");
		User customer = freshUser(customerEmail.toLowerCase());
		var booked = appointmentService.book(customerPrincipal(customer), branch.getId(),
				new CreateAppointmentRequest(service.getId(), NOW.plus(Duration.ofHours(2))));
		assertThat(booked).isNotNull();
	}

	// -- Migration safety ----------------------------------------------------

	@Test
	void v21MigrationAddsReviewColumnsAndPreservesLegacyRows() {
		Integer columns = jdbc.queryForObject(
				"SELECT COUNT(*) FROM information_schema.columns "
						+ "WHERE table_name = 'organization' AND column_name IN "
						+ "('rejection_reason', 'decided_at', 'decided_by_user_id')",
				Integer.class);
		assertThat(columns).isEqualTo(3);
		Boolean indexExists = jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_organization_status')",
				Boolean.class);
		assertThat(indexExists).isTrue();

		Organization legacy = organizations.save(new Organization("V21 Legacy " + UUID.randomUUID()));
		var row = jdbc.queryForMap(
				"SELECT status, rejection_reason, decided_at, decided_by_user_id "
						+ "FROM organization WHERE id = ?", legacy.getId());
		assertThat(row.get("status")).isEqualTo("ACTIVE");
		assertThat(row.get("rejection_reason")).isNull();
		assertThat(row.get("decided_at")).isNull();
		assertThat(row.get("decided_by_user_id")).isNull();
	}
}
