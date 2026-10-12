package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
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
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.staff.StaffAssignment;
import com.sourabh.smartqueue.staff.StaffAssignmentRepository;
import com.sourabh.smartqueue.staff.StaffAssignmentStatus;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.user.UserStatus;

/**
 * Phase 2C domain tests: User, roles and StaffAssignment persistence against
 * real PostgreSQL (see ADR-009). Each test rolls back.
 *
 * <p>Own container per test class (see the note in
 * {@code PostgresInfrastructureTests} on why it is not inherited).
 */
@SpringBootTest
@Testcontainers
@Transactional
class UserStaffPersistenceTests {

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
	JdbcTemplate jdbcTemplate;

	@Autowired
	Flyway flyway;

	private Organization newOrganization(String name) {
		return organizations.save(new Organization(name));
	}

	private Branch newBranch(Organization org, String name) {
		return branches.save(new Branch(org, name, "Springfield", "India", ZoneId.of("Asia/Kolkata")));
	}

	private User newUser(Organization org, String email, String displayName) {
		return users.save(new User(org, email, displayName));
	}

	@Test
	void persistsUserWithDefaults() {
		Organization org = newOrganization("City Clinic");
		User saved = newUser(org, "asha@example.com", "Asha");

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(saved.getRoles()).isEmpty();
		assertThat(saved.getCreatedAt()).isNotNull();

		User reloaded = users.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getEmail()).isEqualTo("asha@example.com");
		assertThat(reloaded.getOrganization().getId()).isEqualTo(org.getId());
	}

	@Test
	void userStatusPersistsAsString() {
		Organization org = newOrganization("City Clinic");
		User user = newUser(org, "asha@example.com", "Asha");
		user.setStatus(UserStatus.INACTIVE);
		users.saveAndFlush(user);

		String stored = jdbcTemplate.queryForObject(
				"SELECT status FROM app_user WHERE id = ?", String.class, user.getId());
		assertThat(stored).isEqualTo("INACTIVE");
		assertThat(users.findById(user.getId()).orElseThrow().getStatus())
				.isEqualTo(UserStatus.INACTIVE);
	}

	@Test
	void emailIsNormalizedToLowercase() {
		Organization org = newOrganization("City Clinic");
		User saved = newUser(org, "  Asha@Example.COM ", "Asha");

		assertThat(saved.getEmail()).isEqualTo("asha@example.com");
	}

	@Test
	void duplicateEmailAcrossOrganizationsIsRejected() {
		Organization first = newOrganization("City Clinic");
		Organization second = newOrganization("County Hospital");
		newUser(first, "asha@example.com", "Asha");

		assertThatThrownBy(
				() -> users.saveAndFlush(newUser(second, "ASHA@example.com", "Asha K.")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void userCanHoldMultipleRoles() {
		Organization org = newOrganization("City Clinic");
		User user = newUser(org, "asha@example.com", "Asha");
		user.grantRole(RoleType.CUSTOMER);
		user.grantRole(RoleType.STAFF);
		users.saveAndFlush(user);

		User reloaded = users.findById(user.getId()).orElseThrow();
		assertThat(reloaded.getRoles()).containsExactlyInAnyOrder(RoleType.CUSTOMER, RoleType.STAFF);
	}

	@Test
	void rolesPersistAsStrings() {
		Organization org = newOrganization("City Clinic");
		User user = newUser(org, "asha@example.com", "Asha");
		user.grantRole(RoleType.MANAGER);
		users.saveAndFlush(user);

		String stored = jdbcTemplate.queryForObject(
				"SELECT role FROM user_role WHERE user_id = ?", String.class, user.getId());
		assertThat(stored).isEqualTo("MANAGER");
	}

	@Test
	void duplicateRoleGrantIsRejectedByDatabase() {
		Organization org = newOrganization("City Clinic");
		User user = newUser(org, "asha@example.com", "Asha");
		user.grantRole(RoleType.STAFF);
		users.saveAndFlush(user);

		// The in-memory Set cannot hold duplicates, so bypass it with raw SQL:
		// the primary key must still reject a second identical row.
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO user_role (user_id, role) VALUES (?, ?)",
				user.getId(), "STAFF"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void revokeRoleRemovesGrant() {
		Organization org = newOrganization("City Clinic");
		User user = newUser(org, "asha@example.com", "Asha");
		user.grantRole(RoleType.STAFF);
		user.revokeRole(RoleType.STAFF);
		users.saveAndFlush(user);

		assertThat(users.findById(user.getId()).orElseThrow().getRoles()).isEmpty();
	}

	@Test
	void persistsValidStaffAssignment() {
		Organization org = newOrganization("City Clinic");
		Branch branch = newBranch(org, "Downtown");
		User user = newUser(org, "asha@example.com", "Asha");

		StaffAssignment saved = assignments.save(new StaffAssignment(org, user, branch));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getStatus()).isEqualTo(StaffAssignmentStatus.ACTIVE);

		StaffAssignment reloaded = assignments.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
		assertThat(reloaded.getBranch().getId()).isEqualTo(branch.getId());
		assertThat(reloaded.getOrganization().getId()).isEqualTo(org.getId());
	}

	@Test
	void assignmentStatusPersistsAsString() {
		Organization org = newOrganization("City Clinic");
		Branch branch = newBranch(org, "Downtown");
		User user = newUser(org, "asha@example.com", "Asha");
		StaffAssignment assignment = assignments.save(new StaffAssignment(org, user, branch));
		assignment.setStatus(StaffAssignmentStatus.INACTIVE);
		assignments.saveAndFlush(assignment);

		String stored = jdbcTemplate.queryForObject(
				"SELECT status FROM staff_assignment WHERE id = ?", String.class,
				assignment.getId());
		assertThat(stored).isEqualTo("INACTIVE");
	}

	@Test
	void sameUserAssignedToMultipleBranches() {
		Organization org = newOrganization("City Clinic");
		Branch first = newBranch(org, "Downtown");
		Branch second = newBranch(org, "Uptown");
		User user = newUser(org, "asha@example.com", "Asha");

		assignments.save(new StaffAssignment(org, user, first));
		StaffAssignment other = assignments.save(new StaffAssignment(org, user, second));

		assertThat(other.getId()).isNotNull();
	}

	@Test
	void multipleUsersAssignedToSameBranch() {
		Organization org = newOrganization("City Clinic");
		Branch branch = newBranch(org, "Downtown");
		User first = newUser(org, "asha@example.com", "Asha");
		User second = newUser(org, "vijay@example.com", "Vijay");

		assignments.save(new StaffAssignment(org, first, branch));
		StaffAssignment other = assignments.save(new StaffAssignment(org, second, branch));

		assertThat(other.getId()).isNotNull();
	}

	@Test
	void duplicateStaffAssignmentIsRejected() {
		Organization org = newOrganization("City Clinic");
		Branch branch = newBranch(org, "Downtown");
		User user = newUser(org, "asha@example.com", "Asha");
		assignments.save(new StaffAssignment(org, user, branch));

		assertThatThrownBy(() -> assignments
				.saveAndFlush(new StaffAssignment(org, user, branch)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void crossOrganizationAssignmentIsRejectedByDomainGuard() {
		Organization orgA = newOrganization("City Clinic");
		Organization orgB = newOrganization("County Hospital");
		Branch branchB = newBranch(orgB, "Downtown");
		User userA = newUser(orgA, "asha@example.com", "Asha");

		assertThatThrownBy(() -> new StaffAssignment(orgA, userA, branchB))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void crossOrganizationAssignmentIsRejectedByDatabase() {
		Organization orgA = newOrganization("City Clinic");
		Organization orgB = newOrganization("County Hospital");
		Branch branchB = newBranch(orgB, "Downtown");
		User userA = newUser(orgA, "asha@example.com", "Asha");

		// Bypass the domain guard with raw SQL: the composite foreign keys
		// must still reject a user from A assigned to a branch from B.
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO staff_assignment"
						+ " (id, organization_id, user_id, branch_id, status, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
				UUID.randomUUID(), orgA.getId(), userA.getId(), branchB.getId(), "ACTIVE"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void flywayAppliedV4DomainMigration() {
		assertThat(flyway.info().applied())
				.anySatisfy(applied -> assertThat(applied.getVersion().getVersion()).isEqualTo("4"));

		Integer tables = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables"
						+ " WHERE table_schema = 'public'"
						+ " AND table_name IN ('app_user', 'user_role', 'staff_assignment')",
				Integer.class);
		assertThat(tables).isEqualTo(3);
	}
}
