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
import com.sourabh.smartqueue.branch.BranchStatus;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.organization.OrganizationStatus;

/**
 * Phase 2A domain tests: Organization → Branch persistence against real
 * PostgreSQL (see ADR-006/007). Each test rolls back; the shared container
 * database stays clean between tests.
 *
 * <p>Own container per test class (see the note in
 * {@code PostgresInfrastructureTests} on why it is not inherited).
 */
@SpringBootTest
@Testcontainers
@Transactional
class OrganizationBranchPersistenceTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	OrganizationRepository organizations;

	@Autowired
	BranchRepository branches;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Flyway flyway;

	private Organization newOrganization(String name) {
		return organizations.save(new Organization(name));
	}

	private Branch newBranch(Organization org, String name) {
		Branch branch = new Branch(org, name, "Springfield", "India", ZoneId.of("Asia/Kolkata"));
		branch.setAddressLine1("1 MG Road");
		branch.setPostalCode("560001");
		return branches.save(branch);
	}

	@Test
	void persistsOrganizationWithDefaults() {
		Organization saved = newOrganization("City Clinic");

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getUpdatedAt()).isNotNull();
		assertThat(saved.getCreatedAt()).isEqualTo(saved.getUpdatedAt());

		Organization reloaded = organizations.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getName()).isEqualTo("City Clinic");
	}

	@Test
	void organizationNamesAreNotGloballyUnique() {
		// Display names may repeat across tenants; the UUID is the identity.
		newOrganization("City Clinic");
		Organization duplicate = newOrganization("City Clinic");

		assertThat(duplicate.getId()).isNotNull();
		assertThat(organizations.count()).isEqualTo(2);
	}

	@Test
	void persistsBranchLinkedToOrganization() {
		Organization org = newOrganization("City Clinic");
		Branch saved = newBranch(org, "Downtown");

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getStatus()).isEqualTo(BranchStatus.ACTIVE);

		branches.flush();
		Branch reloaded = branches.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getOrganization().getId()).isEqualTo(org.getId());
	}

	@Test
	void rejectsBranchWithoutOrganization() {
		Branch orphan = new Branch(null, "Nowhere", "Springfield", "India", ZoneId.of("Asia/Kolkata"));

		assertThatThrownBy(() -> branches.saveAndFlush(orphan))
				.isInstanceOf(jakarta.validation.ConstraintViolationException.class);
	}

	@Test
	void databaseEnforcesBranchOrganizationForeignKey() {
		UUID ghostOrgId = UUID.randomUUID();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO branch (id, organization_id, name, city, country, status, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
				UUID.randomUUID(), ghostOrgId, "Ghost", "Springfield", "India", "ACTIVE"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void branchNamesAreUniqueWithinAnOrganization() {
		Organization org = newOrganization("City Clinic");
		newBranch(org, "Downtown");

		assertThatThrownBy(() -> branches.saveAndFlush(newBranch(org, "Downtown")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void branchNamesMayRepeatAcrossOrganizations() {
		Organization first = newOrganization("City Clinic");
		Organization second = newOrganization("City Clinic");
		newBranch(first, "Downtown");

		Branch sameNameElsewhere = newBranch(second, "Downtown");

		assertThat(sameNameElsewhere.getId()).isNotNull();
	}

	@Test
	void timestampsAreMaintainedOnUpdate() throws Exception {
		Organization org = newOrganization("City Clinic");
		Branch saved = newBranch(org, "Downtown");

		assertThat(saved.getCreatedAt()).isEqualTo(saved.getUpdatedAt());

		Thread.sleep(15);
		saved.setName("Uptown");
		branches.saveAndFlush(saved);

		Branch reloaded = branches.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
		assertThat(reloaded.getUpdatedAt()).isAfter(saved.getCreatedAt());
	}

	@Test
	void statusesPersistAsStrings() {
		Organization org = newOrganization("City Clinic");
		Branch branch = newBranch(org, "Downtown");
		branch.setStatus(BranchStatus.INACTIVE);
		branches.saveAndFlush(branch);

		String storedOrg = jdbcTemplate.queryForObject(
				"SELECT status FROM organization WHERE id = ?", String.class, org.getId());
		String storedBranch = jdbcTemplate.queryForObject(
				"SELECT status FROM branch WHERE id = ?", String.class, branch.getId());
		assertThat(storedOrg).isEqualTo("ACTIVE");
		assertThat(storedBranch).isEqualTo("INACTIVE");

		assertThat(branches.findById(branch.getId()).orElseThrow().getStatus())
				.isEqualTo(BranchStatus.INACTIVE);
	}

	@Test
	void flywayAppliedV2DomainMigration() {
		assertThat(flyway.info().applied())
				.anySatisfy(applied -> assertThat(applied.getVersion().getVersion()).isEqualTo("2"));

		Integer tables = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables"
						+ " WHERE table_schema = 'public' AND table_name IN ('organization', 'branch')",
				Integer.class);
		assertThat(tables).isEqualTo(2);
	}
}
