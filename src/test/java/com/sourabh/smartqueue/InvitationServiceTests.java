package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.OrganizationInvitationRepository;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.auth.api.InvitationResponse;
import com.sourabh.smartqueue.auth.application.InvitationService;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.email.EmailMessage;
import com.sourabh.smartqueue.email.EmailSender;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.staff.StaffAssignmentRepository;
import com.sourabh.smartqueue.staff.StaffAssignmentStatus;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserEmailConflictException;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Employee invitation lifecycle (see ADR-037).
 *
 * <p>NOT transactional: acceptance tokens arrive only by email
 * (digests persist), and after-commit delivery fires on real
 * commits. Fixtures are unique per test. Time rides a manual
 * project Clock.
 */
@Testcontainers
@SpringBootTest
class InvitationServiceTests {

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
	BranchRepository branches;
	@Autowired
	UserRepository users;
	@Autowired
	PasswordCredentialService credentials;
	@Autowired
	OrganizationInvitationRepository invitations;
	@Autowired
	StaffAssignmentRepository assignments;
	@Autowired
	InvitationService invitationsService;
	@Autowired
	OrganizationMembershipRepository memberships;
	@Autowired
	EmailSender sender;
	@Autowired
	PlatformTransactionManager transactionManager;

	/**
	 * Reloads a user with associations initialized (see
	 * RegistrationServiceTests — detached rows cannot lazy-load).
	 */
	private User freshUser(String normalizedEmail) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			User user = users.findByEmail(normalizedEmail).orElseThrow();
			user.getRoles().size();
			user.getOrganization().getId();
			return user;
		});
	}

	Organization orgA;
	Organization orgB;
	Branch downtown;
	User adminA;
	User managerA;
	User staffA;
	User customerA;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		ManualClockConfiguration.NOW_REF.set(NOW);
		Mockito.clearInvocations(sender);
		String tag = UUID.randomUUID().toString().substring(0, 8);
		orgA = organizations.save(new Organization("Invite A " + tag));
		orgB = organizations.save(new Organization("Invite B " + tag));
		downtown = branches.save(new Branch(orgA, "Downtown " + tag, "Bhopal", "India",
				ZoneId.of("Asia/Kolkata")));
		adminA = user(orgA, "adm-" + tag, RoleType.ADMIN);
		managerA = user(orgA, "mgr-" + tag, RoleType.MANAGER);
		staffA = user(orgA, "staff-" + tag, RoleType.STAFF);
		customerA = user(orgA, "cust-" + tag, RoleType.CUSTOMER);
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
		ManualClockConfiguration.NOW_REF.set(NOW);
	}

	private User user(Organization org, String local, RoleType role) {
		User user = users.save(new User(org, local + "@example.com", local));
		user.grantRole(role);
		User saved = users.save(user);
		// Mirrors the V20 backfill: operational fixtures carry the ACTIVE
		// membership row the policy actually authorizes on (ADR-040).
		if (role != RoleType.CUSTOMER) {
			memberships.save(new OrganizationMembership(saved, org,
					MembershipRole.valueOf(role.name())));
		}
		return saved;
	}

	private AuthenticatedPrincipal principal(User user, RoleType... roles) {
		return new AuthenticatedPrincipal(user.getId(),
				user.getOrganization().getId(), user.getEmail(), Set.of(roles));
	}

	private String lastEmailedToken() {
		ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
		Mockito.verify(sender, Mockito.atLeastOnce()).send(captor.capture());
		Matcher matcher = TOKEN_LINK.matcher(captor.getValue().bodyText());
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	private String email(String tag) {
		return "inv-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

	@Test
	void adminInvitesManagerAndManagerInvitesStaff() {
		InvitationResponse managerInvite = invitationsService.invite(
				principal(adminA, RoleType.ADMIN), email("m"), RoleType.MANAGER, null);

		assertThat(managerInvite.status()).isEqualTo("PENDING");
		assertThat(managerInvite.branchId()).isNull();

		InvitationResponse staffInvite = invitationsService.invite(
				principal(managerA, RoleType.MANAGER), email("s"), RoleType.STAFF,
				downtown.getId());

		assertThat(staffInvite.role()).isEqualTo("STAFF");
		assertThat(staffInvite.branchId()).isEqualTo(downtown.getId());
		assertThat(lastEmailedToken()).isNotBlank();
	}

	@Test
	void roleEscalationIsRefused() {
		// Managers cannot create managers; nobody invites admins.
		assertThatThrownBy(() -> invitationsService.invite(
				principal(managerA, RoleType.MANAGER), email("m"), RoleType.MANAGER, null))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> invitationsService.invite(
				principal(adminA, RoleType.ADMIN), email("a"), RoleType.ADMIN, null))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> invitationsService.invite(
				principal(adminA, RoleType.ADMIN), email("c"), RoleType.CUSTOMER, null))
				.isInstanceOf(InvalidRequestException.class);
		// Staff and customers cannot invite at all.
		assertThatThrownBy(() -> invitationsService.invite(
				principal(staffA, RoleType.STAFF), email("s"), RoleType.STAFF, downtown.getId()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> invitationsService.invite(
				principal(customerA, RoleType.CUSTOMER), email("s"), RoleType.STAFF,
				downtown.getId()))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void staffInviteRequiresOwnBranch() {
		assertThatThrownBy(() -> invitationsService.invite(
				principal(managerA, RoleType.MANAGER), email("s"), RoleType.STAFF, null))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> invitationsService.invite(
				principal(managerA, RoleType.MANAGER), email("s"), RoleType.STAFF,
				UUID.randomUUID()))
				.isInstanceOf(ResourceNotFoundException.class);
		// Managers are organization-wide: a branch is rejected, not ignored.
		assertThatThrownBy(() -> invitationsService.invite(
				principal(adminA, RoleType.ADMIN), email("m"), RoleType.MANAGER,
				downtown.getId()))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void existingEmailConflictsHonestly() {
		assertThatThrownBy(() -> invitationsService.invite(
				principal(adminA, RoleType.ADMIN), customerA.getEmail(), RoleType.STAFF,
				downtown.getId()))
				.isInstanceOf(UserEmailConflictException.class);
	}

	@Test
	void acceptCreatesAccountCredentialAndAssignment() {
		String address = email("acc");
		invitationsService.invite(principal(managerA, RoleType.MANAGER), address,
				RoleType.STAFF, downtown.getId());
		String rawToken = lastEmailedToken();

		InvitationService.Acceptance acceptance = invitationsService.accept(
				rawToken, address, "Sam New", "hire-pass-1", "10.0.0.7");

		assertThat(acceptance.organizationId()).isEqualTo(orgA.getId());
		User user = freshUser(address.toLowerCase());
		assertThat(user.getId()).isEqualTo(acceptance.userId());
		assertThat(user.getRoles()).containsExactly(RoleType.STAFF);
		assertThat(credentials.matches(user.getId(), "hire-pass-1")).isTrue();
		assertThat(assignments.existsByUserIdAndBranchIdAndStatus(user.getId(),
				downtown.getId(), StaffAssignmentStatus.ACTIVE)).isTrue();
		assertThat(invitations.findLiveByOrganizationAndEmail(
				orgA.getId(), address.toLowerCase(), NOW)).isEmpty();
		// The acceptance also grounds the ACTIVE membership the policy
		// authorizes on (ADR-040) — acceptance is the only path that
		// creates it, from the server-side invitation role.
		assertThat(memberships.findByUserIdAndOrganizationId(user.getId(), orgA.getId()))
				.get()
				.satisfies(m -> {
					assertThat(m.getRole()).isEqualTo(MembershipRole.STAFF);
					assertThat(m.getStatus())
							.isEqualTo(com.sourabh.smartqueue.membership.MembershipStatus.ACTIVE);
				});

		// Reuse is dead.
		assertThatThrownBy(() -> invitationsService.accept(
				rawToken, address, "Sam New", "hire-pass-1", "10.0.0.7"))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void acceptRejectsWrongEmailGarbageExpiryAndWeakPassword() {
		String address = email("rej");
		invitationsService.invite(principal(managerA, RoleType.MANAGER), address,
				RoleType.STAFF, downtown.getId());
		String rawToken = lastEmailedToken();

		assertThatThrownBy(() -> invitationsService.accept(
				"not-a-real-token", address, "X", "hire-pass-1", "10.0.0.7"))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> invitationsService.accept(
				rawToken, email("other"), "X", "hire-pass-1", "10.0.0.7"))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> invitationsService.accept(
				rawToken, address, "X", "short-1", "10.0.0.7"))
				.isInstanceOf(InvalidRequestException.class);

		ManualClockConfiguration.NOW_REF.set(NOW.plus(Duration.ofDays(8)));
		assertThatThrownBy(() -> invitationsService.accept(
				rawToken, address, "X", "hire-pass-1", "10.0.0.7"))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void revokeAndResendBehave() {
		String address = email("rr");
		InvitationResponse invited = invitationsService.invite(
				principal(managerA, RoleType.MANAGER), address, RoleType.STAFF, downtown.getId());
		String first = lastEmailedToken();

		invitationsService.revoke(principal(managerA, RoleType.MANAGER), invited.id());
		// Revoked tokens are dead; revoke is idempotent.
		assertThatThrownBy(() -> invitationsService.accept(
				first, address, "X", "hire-pass-1", "10.0.0.7"))
				.isInstanceOf(InvalidRequestException.class);
		invitationsService.revoke(principal(managerA, RoleType.MANAGER), invited.id());

		// Resend after revoke issues a fresh live token.
		Mockito.clearInvocations(sender);
		InvitationResponse reissued = invitationsService.resend(
				principal(managerA, RoleType.MANAGER), invited.id());
		String second = lastEmailedToken();
		assertThat(second).isNotEqualTo(first);
		InvitationService.Acceptance acceptance = invitationsService.accept(
				second, address, "Re Hired", "hire-pass-2", "10.0.0.7");
		assertThat(acceptance.organizationId()).isEqualTo(orgA.getId());
		assertThat(reissued.status()).isEqualTo("PENDING");
	}

	@Test
	void crossOrganizationInvitationsStayInvisible() {
		User adminB = user(orgB, "adm-b-" + UUID.randomUUID(), RoleType.ADMIN);
		String address = email("xo");
		InvitationResponse invited = invitationsService.invite(
				principal(adminB, RoleType.ADMIN), address, RoleType.STAFF,
				branches.save(new Branch(orgB, "Elsewhere", "Pune", "India",
						ZoneId.of("Asia/Kolkata"))).getId());

		assertThatThrownBy(() -> invitationsService.revoke(
				principal(managerA, RoleType.MANAGER), invited.id()))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThat(invitationsService.list(
				principal(managerA, RoleType.MANAGER), 0, 20, java.util.List.of())
				.content().stream().noneMatch(row -> row.id().equals(invited.id()))).isTrue();
	}
}

