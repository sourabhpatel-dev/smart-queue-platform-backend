package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.MembershipStatus;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.staff.StaffAssignment;
import com.sourabh.smartqueue.staff.StaffAssignmentRepository;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Phase 4R.2 domain tests: {@link OrganizationMembership} persistence plus
 * the nullable customer organization column (see ADR-040 and the V20
 * migration). Each test rolls back. Own container per test class (the
 * convention from {@code UserStaffPersistenceTests}).
 *
 * <p>Authorization behavior is intentionally NOT tested here — this phase is
 * the persistence foundation only.
 */
@SpringBootTest
@Testcontainers
@Transactional
class OrganizationMembershipPersistenceTests {

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
	StaffAssignmentRepository assignments;

	@Autowired
	OrganizationMembershipRepository memberships;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Flyway flyway;

	private Organization newOrganization(String name) {
		return organizations.save(new Organization(name));
	}

	private Branch newBranch(Organization org, String name) {
		return branches.save(new Branch(org, name, "Springfield", "India", ZoneId.of("Asia/Kolkata")));
	}

	@Test
	void persistsMembershipWithDefaults() {
		Organization org = newOrganization("City Clinic");
		User user = users.save(new User(org, "sam@example.com", "Sam"));

		OrganizationMembership saved = memberships.save(
				new OrganizationMembership(user, org, MembershipRole.STAFF));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getRole()).isEqualTo(MembershipRole.STAFF);
		assertThat(saved.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
		assertThat(saved.getCreatedAt()).isNotNull();

		OrganizationMembership reloaded = memberships.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
		assertThat(reloaded.getOrganization().getId()).isEqualTo(org.getId());
	}

	@Test
	void roleAndStatusPersistAsStrings() {
		Organization org = newOrganization("City Clinic");
		User user = users.save(new User(org, "mira@example.com", "Mira"));
		OrganizationMembership membership = memberships.save(
				new OrganizationMembership(user, org, MembershipRole.MANAGER));
		membership.setStatus(MembershipStatus.INACTIVE);
		memberships.saveAndFlush(membership);

		var row = jdbcTemplate.queryForMap(
				"SELECT role, status FROM organization_membership WHERE id = ?",
				membership.getId());
		assertThat(row.get("role")).isEqualTo("MANAGER");
		assertThat(row.get("status")).isEqualTo("INACTIVE");
	}

	@Test
	void duplicateMembershipForSameUserAndOrganizationIsRejected() {
		Organization org = newOrganization("City Clinic");
		User user = users.save(new User(org, "sam@example.com", "Sam"));
		memberships.saveAndFlush(
				new OrganizationMembership(user, org, MembershipRole.STAFF));

		assertThatThrownBy(() -> memberships.saveAndFlush(
				new OrganizationMembership(user, org, MembershipRole.MANAGER)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void duplicateMembershipIsRejectedByDatabase() {
		Organization org = newOrganization("City Clinic");
		User user = users.save(new User(org, "sam@example.com", "Sam"));
		memberships.saveAndFlush(
				new OrganizationMembership(user, org, MembershipRole.STAFF));

		// Bypass the entity layer: the unique constraint itself must hold.
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO organization_membership"
						+ " (id, user_id, organization_id, role, status, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
				UUID.randomUUID(), user.getId(), org.getId(), "ADMIN", "ACTIVE"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void sameUserCanHoldMembershipsAtDifferentOrganizations() {
		Organization first = newOrganization("City Clinic");
		Organization second = newOrganization("County Hospital");
		User user = users.save(new User(first, "sam@example.com", "Sam"));

		memberships.save(new OrganizationMembership(user, first, MembershipRole.STAFF));
		memberships.save(new OrganizationMembership(user, second, MembershipRole.MANAGER));

		assertThat(memberships.findByUserIdAndStatus(user.getId(), MembershipStatus.ACTIVE))
				.hasSize(2);
	}

	@Test
	void inactiveMembershipIsNotReturnedByActiveQueries() {
		Organization org = newOrganization("City Clinic");
		User user = users.save(new User(org, "sam@example.com", "Sam"));
		OrganizationMembership membership = memberships.save(
				new OrganizationMembership(user, org, MembershipRole.STAFF));
		membership.setStatus(MembershipStatus.INACTIVE);
		memberships.saveAndFlush(membership);

		assertThat(memberships.findByUserIdAndStatus(user.getId(), MembershipStatus.ACTIVE))
				.isEmpty();
		assertThat(memberships.findByUserIdAndStatus(user.getId(), MembershipStatus.INACTIVE))
				.hasSize(1);
	}

	@Test
	void repositoryQueriesResolveScopedGrants() {
		Organization org = newOrganization("City Clinic");
		User sam = users.save(new User(org, "sam@example.com", "Sam"));
		User mira = users.save(new User(org, "mira@example.com", "Mira"));
		memberships.save(new OrganizationMembership(sam, org, MembershipRole.STAFF));
		memberships.save(new OrganizationMembership(mira, org, MembershipRole.MANAGER));

		assertThat(memberships.existsByUserIdAndOrganizationId(sam.getId(), org.getId())).isTrue();
		assertThat(memberships.findByUserIdAndOrganizationId(sam.getId(), org.getId()))
				.get()
				.satisfies(m -> assertThat(m.getRole()).isEqualTo(MembershipRole.STAFF));
		assertThat(memberships.findByOrganizationIdAndStatus(org.getId(), MembershipStatus.ACTIVE))
				.hasSize(2);
	}

	@Test
	void persistsCustomerWithNullOrganization() {
		// The ADR-040 customer shape: platform-level identity, no org.
		User saved = users.save(new User("asha@example.com", "Asha"));
		users.flush();

		assertThat(saved.getOrganization()).isNull();
		User reloaded = users.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getOrganization()).isNull();

		// Still grantable as a customer with no membership anywhere.
		reloaded.grantRole(RoleType.CUSTOMER);
		users.saveAndFlush(reloaded);
		assertThat(users.findById(saved.getId()).orElseThrow().getRoles())
				.containsExactly(RoleType.CUSTOMER);
	}

	@Test
	void nullOrganizationColumnIsPersistedAsSqlNull() {
		User user = users.save(new User("asha@example.com", "Asha"));
		users.flush();

		Integer orgs = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM app_user WHERE id = ? AND organization_id IS NULL",
				Integer.class, user.getId());
		assertThat(orgs).isEqualTo(1);
	}

	@Test
	void membershipConstructorRejectsMissingLegs() {
		Organization org = newOrganization("City Clinic");
		User user = users.save(new User(org, "sam@example.com", "Sam"));

		assertThatThrownBy(() -> new OrganizationMembership(null, org, MembershipRole.STAFF))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OrganizationMembership(user, null, MembershipRole.STAFF))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OrganizationMembership(user, org, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void staffAssignmentStillPinsUserAndBranchToOneOrganization() {
		// The V20 decision: staff_assignment keeps its composite FK, now
		// enforcing that its user has that organization as the primary one.
		// Operational users (org populated) still assign normally.
		Organization org = newOrganization("City Clinic");
		Branch branch = newBranch(org, "Downtown");
		User user = users.save(new User(org, "sam@example.com", "Sam"));

		StaffAssignment saved = assignments.save(new StaffAssignment(org, user, branch));
		assertThat(saved.getId()).isNotNull();

		// A platform-level customer (organization_id NULL) can no longer be
		// assigned to any branch: the composite FK has no matching parent.
		User customer = users.save(new User("asha@example.com", "Asha"));
		users.flush();
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO staff_assignment"
						+ " (id, organization_id, user_id, branch_id, status, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
				UUID.randomUUID(), org.getId(), customer.getId(), branch.getId(), "ACTIVE"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void flywayAppliedV20MembershipMigration() {
		assertThat(flyway.info().applied())
				.anySatisfy(applied -> assertThat(applied.getVersion().getVersion()).isEqualTo("20"));

		Integer tables = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables"
						+ " WHERE table_schema = 'public'"
						+ " AND table_name = 'organization_membership'",
				Integer.class);
		assertThat(tables).isEqualTo(1);

		// The un-pinned customer legs exist as plain FKs now.
		List<String> constraints = jdbcTemplate.queryForList(
				"SELECT constraint_name FROM information_schema.table_constraints"
						+ " WHERE constraint_name IN"
						+ " ('fk_appointment_customer', 'fk_entry_customer',"
						+ " 'fk_appointment_customer_organization', 'fk_entry_customer_organization')"
						+ " ORDER BY constraint_name",
				String.class);
		assertThat(constraints).containsExactly("fk_appointment_customer", "fk_entry_customer");
	}
}
