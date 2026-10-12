package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.holiday.HolidayConflictException;
import com.sourabh.smartqueue.holiday.api.CreateHolidayRequest;
import com.sourabh.smartqueue.holiday.application.HolidayService;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.staff.StaffAssignment;
import com.sourabh.smartqueue.staff.StaffAssignmentRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.workinghour.WorkingHourConflictException;
import com.sourabh.smartqueue.workinghour.api.CreateWorkingHourRequest;
import com.sourabh.smartqueue.workinghour.application.WorkingHourService;

/**
 * Working-hour/holiday management (Phase 4I): operational reads,
 * management writes, scope-safe denials, and constraint-arbitrated
 * conflicts. Each test rolls back.
 */
@Testcontainers
@SpringBootTest
@Transactional
class CalendarManagementTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	OrganizationRepository organizations;
	@Autowired
	BranchRepository branches;
	@Autowired
	UserRepository users;

	@Autowired
	OrganizationMembershipRepository memberships;
	@Autowired
	StaffAssignmentRepository assignments;
	@Autowired
	WorkingHourService workingHours;
	@Autowired
	HolidayService holidays;
	@Autowired
	PlatformTransactionManager transactionManager;

	Organization orgA;
	Organization orgB;
	Branch downtown;
	User managerA;
	User staffA;
	User customerA;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		orgA = organizations.save(new Organization("Cal A " + tag));
		orgB = organizations.save(new Organization("Cal B " + tag));
		downtown = branches.save(new Branch(orgA, "Downtown " + tag, "Bhopal", "India",
				ZoneId.of("Asia/Kolkata")));
		managerA = user(orgA, "mgr-" + tag, RoleType.MANAGER);
		staffA = user(orgA, "staff-" + tag, RoleType.STAFF);
		assignments.save(new StaffAssignment(orgA, staffA, downtown));
		customerA = user(orgA, "cust-" + tag, RoleType.CUSTOMER);
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private User user(Organization org, String local, RoleType role) {
		User user = users.save(new User(org, local + "@example.com", local));
		user.grantRole(role);
		User saved = users.save(user);
		// Mirrors the V20 backfill for fixtures (ADR-040): operational
		// roles carry the ACTIVE membership the policy authorizes on.
		if (role != RoleType.CUSTOMER) {
			memberships.save(new OrganizationMembership(saved, org,
					MembershipRole.valueOf(role.name())));
		}
		return saved;
	}

	/**
	 * Commits the fixtures created so far so an isolated conflict
	 * probe can see them, then opens a fresh rollback transaction for
	 * the remaining steps (rolled back at test end as usual).
	 *
	 * <p>Why: the probe must run in its own top-level transaction
	 * (see inIsolatedTransaction) — a PostgreSQL error aborts the
	 * whole transaction it fires in, so firing the deliberately
	 * conflicting write inside the shared test transaction would
	 * poison every later statement ("current transaction is
	 * aborted"). And a separate top-level transaction cannot see the
	 * test transaction's uncommitted rows, hence the commit first.
	 * Fixture values are random per test and every read in this
	 * suite is branch-scoped, so the committed rows cannot leak into
	 * another test's assertions.
	 */
	private void commitFixturesForIsolatedProbe() {
		TestTransaction.flagForCommit();
		TestTransaction.end();
		TestTransaction.start();
	}

	/**
	 * Runs the conflicting write in its own top-level transaction,
	 * mirroring production where every request commits (or rolls
	 * back) alone. The exclusion/unique violation rolls back only
	 * this inner transaction; the calling test transaction stays
	 * usable. The translated conflict exception still propagates and
	 * is still asserted — nothing is caught and suppressed.
	 */
	private <T> T inIsolatedTransaction(java.util.function.Supplier<T> write) {
		TransactionTemplate isolated = new TransactionTemplate(transactionManager);
		isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return isolated.execute(status -> write.get());
	}

	private AuthenticatedPrincipal principal(User user, RoleType... roles) {
		return new AuthenticatedPrincipal(user.getId(),
				user.getOrganization().getId(), user.getEmail(), Set.of(roles));
	}

	@Test
	void managerCreatesListsAndDeletesIntervals() {
		assertThat(workingHours.list(
				principal(managerA, RoleType.MANAGER), downtown.getId())).isEmpty();

		workingHours.create(principal(managerA, RoleType.MANAGER), downtown.getId(),
				new CreateWorkingHourRequest("MONDAY", LocalTime.of(9, 0), LocalTime.of(13, 0)));
		workingHours.create(principal(managerA, RoleType.MANAGER), downtown.getId(),
				new CreateWorkingHourRequest("monday", LocalTime.of(14, 0), LocalTime.of(18, 0)));

		var rows = workingHours.list(principal(managerA, RoleType.MANAGER), downtown.getId());
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).startTime()).isEqualTo(LocalTime.of(9, 0));
		assertThat(rows.get(0).dayOfWeek()).isEqualTo(DayOfWeek.MONDAY.name());

		// Overlaps arbitrate to 409, adjacency is allowed. The overlap
		// probe runs isolated (see inIsolatedTransaction): its
		// exclusion violation must not abort the shared test
		// transaction.
		commitFixturesForIsolatedProbe();
		assertThatThrownBy(() -> inIsolatedTransaction(() ->
				workingHours.create(principal(managerA, RoleType.MANAGER),
						downtown.getId(),
						new CreateWorkingHourRequest("MONDAY", LocalTime.of(12, 0), LocalTime.of(15, 0)))))
				.isInstanceOf(WorkingHourConflictException.class);
		workingHours.create(principal(managerA, RoleType.MANAGER), downtown.getId(),
				new CreateWorkingHourRequest("MONDAY", LocalTime.of(13, 0), LocalTime.of(14, 0)));

		// Invalid shape is 400, not a constraint surprise.
		assertThatThrownBy(() -> workingHours.create(principal(managerA, RoleType.MANAGER),
				downtown.getId(),
				new CreateWorkingHourRequest("FUNDAY", LocalTime.of(9, 0), LocalTime.of(10, 0))))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> workingHours.create(principal(managerA, RoleType.MANAGER),
				downtown.getId(),
				new CreateWorkingHourRequest("MONDAY", LocalTime.of(10, 0), LocalTime.of(9, 0))))
				.isInstanceOf(InvalidRequestException.class);

		workingHours.delete(principal(managerA, RoleType.MANAGER), rows.get(0).id());
		assertThat(workingHours.list(principal(managerA, RoleType.MANAGER), downtown.getId()))
				.hasSize(2);
	}

	@Test
	void staffReadsButCannotWriteAndCustomersCannotRead() {
		workingHours.create(principal(managerA, RoleType.MANAGER), downtown.getId(),
				new CreateWorkingHourRequest("MONDAY", LocalTime.of(9, 0), LocalTime.of(18, 0)));

		assertThat(workingHours.list(
				principal(staffA, RoleType.STAFF), downtown.getId())).hasSize(1);
		assertThatThrownBy(() -> workingHours.create(principal(staffA, RoleType.STAFF),
				downtown.getId(),
				new CreateWorkingHourRequest("TUESDAY", LocalTime.of(9, 0), LocalTime.of(18, 0))))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> workingHours.list(
				principal(customerA, RoleType.CUSTOMER), downtown.getId()))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void foreignBranchIsScopeSafe404() {
		Branch foreign = branches.save(new Branch(orgB, "Elsewhere", "Pune", "India",
				ZoneId.of("Asia/Kolkata")));

		assertThatThrownBy(() -> workingHours.list(
				principal(managerA, RoleType.MANAGER), foreign.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> workingHours.delete(
				principal(managerA, RoleType.MANAGER), UUID.randomUUID()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void holidaysCreateListDeleteWithDuplicate409() {
		LocalDate diwali = LocalDate.of(2026, 11, 8);
		var created = holidays.create(principal(managerA, RoleType.MANAGER), downtown.getId(),
				new CreateHolidayRequest(diwali, "Diwali"));

		assertThat(created.date()).isEqualTo(diwali);
		assertThat(holidays.list(
				principal(staffA, RoleType.STAFF), downtown.getId())).hasSize(1);
		// The duplicate probe runs isolated (see
		// inIsolatedTransaction): its unique violation must not abort
		// the shared test transaction.
		commitFixturesForIsolatedProbe();
		assertThatThrownBy(() -> inIsolatedTransaction(() ->
				holidays.create(principal(managerA, RoleType.MANAGER),
						downtown.getId(), new CreateHolidayRequest(diwali, "Diwali again"))))
				.isInstanceOf(HolidayConflictException.class);
		assertThatThrownBy(() -> holidays.delete(
				principal(staffA, RoleType.STAFF), created.id()))
				.isInstanceOf(AccessDeniedException.class);

		holidays.delete(principal(managerA, RoleType.MANAGER), created.id());
		assertThat(holidays.list(
				principal(managerA, RoleType.MANAGER), downtown.getId())).isEmpty();
	}
}




