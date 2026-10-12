package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.TenantAccessPolicy;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.MembershipStatus;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.QueueStatus;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.staff.StaffAssignment;
import com.sourabh.smartqueue.staff.StaffAssignmentRepository;
import com.sourabh.smartqueue.staff.StaffAssignmentStatus;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Authorization policy tests (see ADR-021, ADR-040): organization
 * isolation grounded in ACTIVE memberships, branch ownership, and the
 * role rule (managers org-wide, staff by assignment). Customers are
 * platform-level identities — they carry no organization and are denied
 * organization-scoped operations (their actions are resource-derived at
 * the service layer, never through this policy). Each test rolls back.
 * Own container per test class.
 */
@SpringBootTest
@Testcontainers
@Transactional
class TenantAccessPolicyTests {

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
	StaffAssignmentRepository assignments;

	@Autowired
	UserRepository users;

	@Autowired
	OrganizationMembershipRepository memberships;

	@Autowired
	QueueRepository queues;

	@Autowired
	TenantAccessPolicy policy;

	Organization orgA;
	Organization orgB;
	Branch branchA;
	Branch branchB;
	Service serviceA;
	Queue queueA;
	User staff;
	User manager;
	User customer;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		orgA = organizations.save(new Organization("Policy A " + UUID.randomUUID()));
		orgB = organizations.save(new Organization("Policy B " + UUID.randomUUID()));
		branchA = branches.save(new Branch(orgA, "Downtown", "Bhopal", "India", KOLKATA));
		branchB = branches.save(new Branch(orgB, "Downtown", "Bhopal", "India", KOLKATA));
		serviceA = services.save(
				new Service(branchA, "Consultation", Duration.ofMinutes(30)));
		queueA = queues.save(new Queue(orgA, branchA, serviceA));
		staff = userWithRole(orgA, RoleType.STAFF);
		manager = userWithRole(orgA, RoleType.MANAGER);
		customer = userWithRole(orgA, RoleType.CUSTOMER);
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	/**
	 * Users get the legacy home-organization column like the rest of the
	 * suite, plus — for operational roles — the ACTIVE membership row that
	 * the policy actually authorizes on (ADR-040). Customers hold no
	 * membership at all.
	 */
	private User userWithRole(Organization org, RoleType role) {
		User user = users.save(new User(org,
				role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com", "Policy"));
		user.grantRole(role);
		user = users.save(user);
		if (role != RoleType.CUSTOMER) {
			memberships.save(new OrganizationMembership(user, org,
					MembershipRole.valueOf(role.name())));
		}
		return user;
	}

	/**
	 * Builds the principal the way AuthService does: the organization is
	 * resolved from an ACTIVE membership — customers and users without
	 * one resolve to {@code null}.
	 */
	private AuthenticatedPrincipal principalOf(User user) {
		UUID organizationId;
		if (!user.getRoles().stream().anyMatch(role -> role != RoleType.CUSTOMER)) {
			organizationId = null;
		} else {
			UUID home = user.getOrganization() == null ? null : user.getOrganization().getId();
			List<OrganizationMembership> active = memberships.findByUserIdAndStatus(
					user.getId(), MembershipStatus.ACTIVE);
			organizationId = active.stream().map(m -> m.getOrganization().getId()).toList()
					.contains(home) ? home
					: active.size() == 1 ? active.get(0).getOrganization().getId() : null;
		}
		return new AuthenticatedPrincipal(user.getId(), organizationId,
				user.getEmail(), Set.copyOf(user.getRoles()));
	}

	@Test
	void sameOrganizationIsAllowed() {
		policy.requireOrganizationAccess(principalOf(staff), orgA.getId());
	}

	@Test
	void otherOrganizationIsDenied() {
		assertThatThrownBy(() -> policy.requireOrganizationAccess(principalOf(staff), orgB.getId()))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void unknownBranchIsDenied() {
		assertThatThrownBy(() -> policy.requireBranchAccess(principalOf(manager), UUID.randomUUID()))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void branchOfOtherOrganizationIsDenied() {
		assertThatThrownBy(() -> policy.requireBranchAccess(principalOf(manager), branchB.getId()))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void managerIsOrganizationWide() {
		policy.requireBranchAccess(principalOf(manager), branchA.getId());
	}

	@Test
	void adminIsOrganizationWide() {
		User admin = userWithRole(orgA, RoleType.ADMIN);

		policy.requireBranchAccess(principalOf(admin), branchA.getId());
	}

	@Test
	void staffWithActiveAssignmentIsAllowed() {
		assignments.save(new StaffAssignment(orgA, staff, branchA));

		policy.requireBranchAccess(principalOf(staff), branchA.getId());
	}

	@Test
	void staffWithoutAssignmentIsDenied() {
		assertThatThrownBy(() -> policy.requireBranchAccess(principalOf(staff), branchA.getId()))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void staffWithInactiveAssignmentIsDenied() {
		StaffAssignment assignment = assignments.save(new StaffAssignment(orgA, staff, branchA));
		assignment.setStatus(StaffAssignmentStatus.INACTIVE);
		assignments.save(assignment);

		assertThatThrownBy(() -> policy.requireBranchAccess(principalOf(staff), branchA.getId()))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void staffAssignmentAtOtherBranchDoesNotGrantAccess() {
		Branch other = branches.save(new Branch(orgA, "Uptown", "Bhopal", "India", KOLKATA));
		assignments.save(new StaffAssignment(orgA, staff, other));

		assertThatThrownBy(() -> policy.requireBranchAccess(principalOf(staff), branchA.getId()))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void customerIsDeniedOrganizationScopedOperations() {
		// Platform-level customers hold no organization: the policy
		// denies them outright (their actions are resource-derived at
		// the service layer). A legacy home-organization value is never
		// an authorization input (ADR-040).
		assertThatThrownBy(() -> policy.requireOrganizationAccess(principalOf(customer), orgA.getId()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> policy.requireBranchAccess(principalOf(customer), branchA.getId()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> policy.requireBranchAccess(principalOf(customer), branchB.getId()))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void organizationWithoutMembershipIsDenied() {
		User rogue = userWithRole(orgA, RoleType.ADMIN);
		// The V20 backfill is what creates this user's row in production;
		// deleting it here simulates a revoked-or-never-provisioned grant.
		memberships.deleteAll(memberships.findByUserIdAndStatus(
				rogue.getId(), MembershipStatus.ACTIVE));

		assertThatThrownBy(() -> policy.requireOrganizationAccess(principalOf(rogue), orgA.getId()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> policy.requireBranchAccess(principalOf(rogue), branchA.getId()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> policy.requireOrganizationManagement(principalOf(rogue)))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void inactiveMembershipCannotAuthorize() {
		OrganizationMembership membership = memberships
				.findByUserIdAndOrganizationId(staff.getId(), orgA.getId()).orElseThrow();
		membership.setStatus(MembershipStatus.INACTIVE);
		memberships.save(membership);

		assertThatThrownBy(() -> policy.requireOrganizationAccess(principalOf(staff), orgA.getId()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> policy.requireBranchAccess(principalOf(staff), branchA.getId()))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void tamperedOrganizationCannotReachAnotherOrganization() {
		// A manager at orgA naming orgB gets the scope-safe denial on
		// every operational spell: organization, branch, roster, queue.
		assignments.save(new StaffAssignment(orgA, staff, branchA));

		assertThatThrownBy(() -> policy.requireOrganizationAccess(principalOf(manager), orgB.getId()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> policy.requireBranchAccess(principalOf(manager), branchB.getId()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> policy.requireBranchOperationalAccess(
				principalOf(manager), branchB.getId()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> policy.requireQueueScopeAccess(principalOf(manager),
				orgB.getId(), branchB.getId(), queueA))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void queueScopeRequiresExactScopeAndBranchAccess() {
		assignments.save(new StaffAssignment(orgA, staff, branchA));

		policy.requireQueueScopeAccess(principalOf(staff),
				orgA.getId(), branchA.getId(), queueA);

		assertThatThrownBy(() -> policy.requireQueueScopeAccess(principalOf(staff),
				orgA.getId(), branchB.getId(), queueA))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> policy.requireQueueScopeAccess(principalOf(staff),
				orgB.getId(), branchB.getId(), queueA))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void customersMaySubscribeOnlyToActiveQueues() {
		policy.requireQueueScopeAccess(principalOf(customer),
				orgA.getId(), branchA.getId(), queueA);

		Queue inactive = queues.save(new Queue(orgA, branchA,
				services.save(new Service(branchA, "Dental " + UUID.randomUUID(),
						Duration.ofMinutes(30)))));
		inactive.setStatus(QueueStatus.INACTIVE);
		queues.save(inactive);
		assertThatThrownBy(() -> policy.requireQueueScopeAccess(principalOf(customer),
				orgA.getId(), branchA.getId(), inactive))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void managersAndAdminsMayManageTheOrganization() {
		policy.requireOrganizationManagement(principalOf(manager));
		policy.requireOrganizationManagement(principalOf(userWithRole(orgA, RoleType.ADMIN)));
	}

	@Test
	void staffAndCustomersMayNotManageTheOrganization() {
		assertThatThrownBy(() -> policy.requireOrganizationManagement(principalOf(staff)))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> policy.requireOrganizationManagement(principalOf(customer)))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void branchManagementRequiresScopeThenManagementRole() {
		policy.requireBranchManagement(principalOf(manager), branchA.getId());

		// Assigned staff pass the scope check but fail the management rule.
		assignments.save(new StaffAssignment(orgA, staff, branchA));
		assertThatThrownBy(() -> policy.requireBranchManagement(principalOf(staff), branchA.getId()))
				.isInstanceOf(AccessDeniedException.class);

		// Foreign branches deny before any role is considered.
		assertThatThrownBy(() -> policy.requireBranchManagement(principalOf(manager), branchB.getId()))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void operationalAccessExcludesCustomersAndUnassignedStaff() {
		policy.requireBranchOperationalAccess(principalOf(manager), branchA.getId());
		policy.requireBranchOperationalAccess(
				principalOf(userWithRole(orgA, RoleType.ADMIN)), branchA.getId());

		assignments.save(new StaffAssignment(orgA, staff, branchA));
		policy.requireBranchOperationalAccess(principalOf(staff), branchA.getId());

		// Rosters are operational data, not token-only displays.
		assertThatThrownBy(
				() -> policy.requireBranchOperationalAccess(principalOf(customer), branchA.getId()))
				.isInstanceOf(AccessDeniedException.class);
		Branch other = branches.save(new Branch(orgA, "Suburb", "Bhopal", "India", KOLKATA));
		assertThatThrownBy(
				() -> policy.requireBranchOperationalAccess(principalOf(staff), other.getId()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(
				() -> policy.requireBranchOperationalAccess(principalOf(manager), branchB.getId()))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void selfOrManagementReads() {
		policy.requireSelfOrManagement(principalOf(staff), staff.getId());
		policy.requireSelfOrManagement(principalOf(manager), staff.getId());

		assertThatThrownBy(
				() -> policy.requireSelfOrManagement(principalOf(staff), manager.getId()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(
				() -> policy.requireSelfOrManagement(principalOf(customer), staff.getId()))
				.isInstanceOf(AccessDeniedException.class);
	}
}
