package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.appointment.AppointmentBookingService;
import com.sourabh.smartqueue.appointment.api.CreateAppointmentRequest;
import com.sourabh.smartqueue.appointment.application.AppointmentService;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.TenantAccessPolicy;
import com.sourabh.smartqueue.auth.application.RegistrationService;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.common.api.ApiPage;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.MembershipStatus;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.notification.application.NotificationService;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.api.JoinQueueRequest;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.api.QueueEntryResponse;
import com.sourabh.smartqueue.queue.application.QueueService;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.workinghour.WorkingHour;
import com.sourabh.smartqueue.workinghour.WorkingHourRepository;

/**
 * The ADR-040 identity model end to end: one platform-level customer
 * interacting with several organizations, and membership-grounded
 * operational authorization (see ADR-040). Each test rolls back; own
 * container per class.
 *
 * <p>These tests use the same CLOCK wall-clock strategy as the rest of
 * the suite (future instants relative to {@code Clock.instant()}, full-day
 * working hours in a DST-free zone — never hardcoded dates): nothing here
 * depends on the wall-clock time of the run.
 */
@SpringBootTest
@Testcontainers
@Transactional
class CustomerPlatformIdentityTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

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
	OrganizationMembershipRepository memberships;
	@Autowired
	AppointmentService appointments;
	@Autowired
	AppointmentBookingService booking;
	@Autowired
	QueueService queueService;
	@Autowired
	NotificationService notifications;
	@Autowired
	TenantAccessPolicy access;
	@Autowired
	RegistrationService registrations;
	@Autowired
	Clock clock;

	Organization orgA;
	Organization orgB;
	Branch branchA;
	Branch branchB;
	Service serviceA;
	Service serviceB;
	Queue queueA;
	Queue queueB;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		orgA = organizations.save(new Organization("Platform Org A " + UUID.randomUUID()));
		orgB = organizations.save(new Organization("Platform Org B " + UUID.randomUUID()));
		branchA = branch(orgA, "Downtown");
		branchB = branch(orgB, "Uptown");
		serviceA = services.save(new Service(branchA, "Consultation", Duration.ofMinutes(30)));
		serviceB = services.save(new Service(branchB, "Consultation", Duration.ofMinutes(30)));
		queueA = queues.save(new Queue(orgA, branchA, serviceA));
		queueB = queues.save(new Queue(orgB, branchB, serviceB));
	}

	private Branch branch(Organization org, String name) {
		Branch branch = branches.save(new Branch(org, name, "Bhopal", "India", KOLKATA));
		for (DayOfWeek day : DayOfWeek.values()) {
			workingHours.save(new WorkingHour(branch, day,
					LocalTime.of(0, 0), LocalTime.of(23, 59)));
		}
		return branch;
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private User customer(String tag) {
		User user = users.save(new User(tag + "@example.com", tag));
		user.grantRole(RoleType.CUSTOMER);
		return users.save(user);
	}

	private AuthenticatedPrincipal principalOf(User user) {
		UUID organizationId = null;
		if (user.getRoles().stream().anyMatch(role -> role != RoleType.CUSTOMER)) {
			List<OrganizationMembership> active = memberships.findByUserIdAndStatus(
					user.getId(), MembershipStatus.ACTIVE);
			organizationId = active.size() == 1
					? active.get(0).getOrganization().getId() : null;
		}
		return new AuthenticatedPrincipal(user.getId(), organizationId,
				user.getEmail(), Set.copyOf(user.getRoles()));
	}

	/** A deterministic bookable instant: 10:00 branch-local on a future date. */
	private Instant slot(int daysAhead) {
		return java.time.LocalDate.now(KOLKATA).plusDays(daysAhead)
				.atTime(10, 0).atZone(KOLKATA).toInstant();
	}

	@Test
	void oneCustomerBooksAtTwoOrganizations() {
		User customer = customer("two-orgs-" + UUID.randomUUID());
		AuthenticatedPrincipal principal = principalOf(customer);
		assertThat(principal.organizationId()).isNull();

		assertThat(appointments.book(principal, branchA.getId(),
				new CreateAppointmentRequest(serviceA.getId(), slot(2))).branchId())
				.isEqualTo(branchA.getId());
		assertThat(appointments.book(principal, branchB.getId(),
				new CreateAppointmentRequest(serviceB.getId(), slot(3))).branchId())
				.isEqualTo(branchB.getId());

		// One account, both appointments visible in one customer listing.
		ApiPage<com.sourabh.smartqueue.appointment.api.AppointmentResponse> page =
				appointments.listAppointments(principal, 0, 20, List.of(), null, null,
						null, null, null, null);
		assertThat(page.totalElements()).isEqualTo(2);
	}

	@Test
	void legacyOrganizationValueDoesNotAuthorize() {
		// The customer keeps a populated legacy home organization from the
		// old registration model — it must neither grant nor deny access
		// elsewhere.
		User customer = users.save(new User(orgA,
				"legacy-" + UUID.randomUUID() + "@example.com", "Legacy"));
		customer.grantRole(RoleType.CUSTOMER);
		customer = users.save(customer);
		AuthenticatedPrincipal principal = principalOf(customer);

		// Books at the legacy organization AND at a different one.
		assertThat(appointments.book(principal, branchA.getId(),
				new CreateAppointmentRequest(serviceA.getId(), slot(2))).branchId())
				.isEqualTo(branchA.getId());
		assertThat(appointments.book(principal, branchB.getId(),
				new CreateAppointmentRequest(serviceB.getId(), slot(3))).branchId())
				.isEqualTo(branchB.getId());

		// Reading the other customer's appointment at the SAME legacy org
		// is still denied (ownership, not organization, is the boundary).
		// A distinct slot avoids the per-service exclusion arbitration.
		User other = customer("other-" + UUID.randomUUID());
		UUID othersAppointment = appointments.book(principalOf(other), branchA.getId(),
				new CreateAppointmentRequest(serviceA.getId(), slot(4))).id();
		assertThatThrownBy(() -> appointments.getAppointment(principal, othersAppointment))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void oneCustomerJoinsQueuesAtTwoOrganizations() {
		User customer = customer("two-queues-" + UUID.randomUUID());
		AuthenticatedPrincipal principal = principalOf(customer);

		QueueEntryResponse entryA = queueService.join(principal, branchA.getId(),
				queueA.getId(), new JoinQueueRequest(null));
		QueueEntryResponse entryB = queueService.join(principal, branchB.getId(),
				queueB.getId(), new JoinQueueRequest(null));

		assertThat(entryA.id()).isNotEqualTo(entryB.id());
		// Both entries stay live-readable by their owner with positions.
		assertThat(queueService.getEntry(principal, entryA.id()).position()).isNotNull();
		assertThat(queueService.getEntry(principal, entryB.id()).position()).isNotNull();
		// Owner cancellation works at both organizations.
		assertThat(queueService.cancelEntry(principal, entryA.id()).status())
				.isEqualTo("CANCELLED");
		assertThat(queueService.cancelEntry(principal, entryB.id()).status())
				.isEqualTo("CANCELLED");
	}

	@Test
	void customerReadsOwnNotificationsAcrossOrganizations() {
		User customer = customer("bell-" + UUID.randomUUID());
		AuthenticatedPrincipal principal = principalOf(customer);

		assertThat(appointments.book(principal, branchA.getId(),
				new CreateAppointmentRequest(serviceA.getId(), slot(2))).branchId())
				.isEqualTo(branchA.getId());
		assertThat(appointments.book(principal, branchB.getId(),
				new CreateAppointmentRequest(serviceB.getId(), slot(3))).branchId())
				.isEqualTo(branchB.getId());

		// The center is recipient-scoped: no organization is named.
		assertThat(notifications.listNotifications(principal, 0, 20, List.of(), null)
				.content()).isEmpty();
		assertThat(notifications.unreadCount(principal)).isZero();
	}

	@Test
	void operationalUserWithoutMembershipIsRejected() {
		User staff = users.save(new User(orgA,
				"nomem-" + UUID.randomUUID() + "@example.com", "No Membership"));
		staff.grantRole(RoleType.STAFF);
		staff = users.save(staff);
		AuthenticatedPrincipal principal = principalOf(staff);
		assertThat(principal.organizationId()).isNull();

		assertThatThrownBy(() -> access.requireOrganizationManagement(principal))
				.isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
	}

	@Test
	void inactiveMembershipCannotAuthorize() {
		User manager = users.save(new User(orgA,
				"inact-" + UUID.randomUUID() + "@example.com", "Inactive"));
		manager.grantRole(RoleType.MANAGER);
		manager = users.save(manager);
		OrganizationMembership membership =
				memberships.save(new OrganizationMembership(manager, orgA, MembershipRole.MANAGER));
		membership.setStatus(MembershipStatus.INACTIVE);
		memberships.save(membership);
		AuthenticatedPrincipal principal = principalOf(manager);
		assertThat(principal.organizationId()).isNull();

		assertThatThrownBy(() -> access.requireOrganizationManagement(principal))
				.isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
	}

	@Test
	void operationalTamperingWithOrganizationIsDenied() {
		User manager = users.save(new User(orgA,
				"tamper-" + UUID.randomUUID() + "@example.com", "Tamper"));
		manager.grantRole(RoleType.MANAGER);
		manager = users.save(manager);
		memberships.save(new OrganizationMembership(manager, orgA, MembershipRole.MANAGER));
		AuthenticatedPrincipal principal = principalOf(manager);
		assertThat(principal.organizationId()).isEqualTo(orgA.getId());

		// A manager at Org A sees Org B's resources as missing, never as data.
		assertThatThrownBy(() -> appointments.book(principal, branchB.getId(),
				new CreateAppointmentRequest(serviceB.getId(), slot(2))))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> queueService.join(principal, branchB.getId(),
				queueB.getId(), new JoinQueueRequest(null)))
				.isInstanceOf(ResourceNotFoundException.class);
	}
}


