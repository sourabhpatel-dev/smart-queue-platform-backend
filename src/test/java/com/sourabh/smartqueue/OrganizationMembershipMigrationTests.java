package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Phase 4R.2 migration proof for V20 (ADR-040): runs the real Flyway chain
 * against representative legacy data — migrate to V19, insert pre-V20
 * shapes, then apply V20 and assert the backfill, the un-pinned customer
 * legs, and the preserved historical rows.
 *
 * <p>Deliberately NOT a {@code @SpringBootTest}: the application's
 * auto-migration would apply V20 before the legacy rows exist, and this
 * test exists to prove exactly that transition. Each test gets a pristine
 * database (schema drop/recreate), so the V19 → V20 walk runs per method.
 */
@Testcontainers
class OrganizationMembershipMigrationTests {

	private static final UUID ORG_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID ORG_B = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
	private static final UUID BRANCH_A = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
	private static final UUID SERVICE_A = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
	private static final UUID QUEUE_A = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
	private static final UUID CUSTOMER_A = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001");
	private static final UUID STAFF_A = UUID.fromString("eeeeeeee-0000-0000-0000-000000000002");
	private static final UUID MANAGER_A = UUID.fromString("eeeeeeee-0000-0000-0000-000000000003");
	private static final UUID ADMIN_A = UUID.fromString("eeeeeeee-0000-0000-0000-000000000004");
	private static final UUID STAFF_B = UUID.fromString("eeeeeeee-0000-0000-0000-000000000005");

	@Container
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	private JdbcTemplate jdbc;
	private Flyway flyway;

	@BeforeEach
	void resetSchemaAndMigrateToV19() {
		DataSource dataSource = new DriverManagerDataSource(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");

		flyway = Flyway.configure()
				.dataSource(dataSource)
				.target(MigrationVersion.fromVersion("19"))
				.load();
		flyway.migrate();
		seedLegacyData();
	}

	private void seedLegacyData() {
		jdbc.update("INSERT INTO organization (id, name, status, created_at, updated_at)"
				+ " VALUES (?, 'City Clinic', 'ACTIVE', NOW(), NOW())", ORG_A);
		jdbc.update("INSERT INTO organization (id, name, status, created_at, updated_at)"
				+ " VALUES (?, 'County Hospital', 'ACTIVE', NOW(), NOW())", ORG_B);
		jdbc.update("INSERT INTO branch (id, organization_id, name, address_line1, city,"
				+ " country, status, time_zone, created_at, updated_at)"
				+ " VALUES (?, ?, 'Downtown', null, 'Bhopal', 'India', 'ACTIVE',"
				+ " 'Asia/Kolkata', NOW(), NOW())", BRANCH_A, ORG_A);
		jdbc.update("INSERT INTO service (id, branch_id, name, description,"
				+ " standard_duration_minutes, status, created_at, updated_at)"
				+ " VALUES (?, ?, 'General Consultation', 'desc', 30, 'ACTIVE', NOW(), NOW())",
				SERVICE_A, BRANCH_A);
		jdbc.update("INSERT INTO queue (id, organization_id, branch_id, service_id, status,"
				+ " created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?, 'ACTIVE', NOW(), NOW())", QUEUE_A, ORG_A, BRANCH_A, SERVICE_A);

		// Legacy shapes: every user owned by exactly one organization.
		insertUser(CUSTOMER_A, ORG_A, "asha@example.com", "ACTIVE", "CUSTOMER");
		insertUser(STAFF_A, ORG_A, "sam@example.com", "ACTIVE", "STAFF");
		insertUser(MANAGER_A, ORG_A, "mira@example.com", "ACTIVE", "MANAGER");
		insertUser(ADMIN_A, ORG_A, "adi@example.com", "ACTIVE", "ADMIN");
		insertUser(STAFF_B, ORG_B, "vijay@example.com", "ACTIVE", "STAFF");
		insertUser(UUID.fromString("eeeeeeee-0000-0000-0000-000000000006"), ORG_A,
				"nina@example.com", "INACTIVE", "STAFF");

		// Historical transactions for the org-A customer.
		jdbc.update("INSERT INTO appointment (id, organization_id, branch_id, service_id,"
				+ " customer_id, start_at, end_at, status, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?, ?, NOW() + interval '20 hours',"
				+ " NOW() + interval '20 hours 30 minutes', 'SCHEDULED', NOW(), NOW())",
				UUID.randomUUID(), ORG_A, BRANCH_A, SERVICE_A, CUSTOMER_A);
		jdbc.update("INSERT INTO queue_entry (id, organization_id, queue_id, customer_id,"
				+ " token_number, business_date, priority, status, joined_at, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?, 1, CURRENT_DATE, 'NORMAL', 'WAITING',"
				+ " NOW() - interval '5 minutes', NOW(), NOW())",
				UUID.randomUUID(), ORG_A, QUEUE_A, CUSTOMER_A);
		jdbc.update("INSERT INTO notification (id, organization_id, recipient_user_id, type,"
				+ " title, message, event_id, created_at, updated_at)"
				+ " VALUES (?, ?, ?, 'QUEUE_CALLED', 't', 'm', ?, NOW(), NOW())",
				UUID.randomUUID(), ORG_A, CUSTOMER_A, UUID.randomUUID());
	}

	private void insertUser(UUID id, UUID organizationId, String email,
			String status, String role) {
		jdbc.update("INSERT INTO app_user (id, organization_id, email, display_name, status,"
				+ " created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
				id, organizationId, email, email.substring(0, email.indexOf('@')), status);
		jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", id, role);
	}

	private void migrateToV20() {
		// A fresh instance without the V19 target: the configured instance
		// is deliberately capped at 19 for the legacy-state phase.
		Flyway uncapped = Flyway.configure().dataSource(flyway.getConfiguration().getDataSource())
				.load();
		flyway = uncapped;
		uncapped.migrate();
	}

	@Test
	void preV20SchemaRequiresOrganizationAndPinsCustomer() {
		// Before V20 the legacy constraint is visible: a customer-less user
		// and a cross-organization appointment are both impossible.
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO app_user (id, organization_id, email, display_name, status,"
						+ " created_at, updated_at) VALUES (?, NULL, 'x@example.com', 'X',"
						+ " 'ACTIVE', NOW(), NOW())", UUID.randomUUID()))
				.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO appointment (id, organization_id, branch_id, service_id,"
						+ " customer_id, start_at, end_at, status, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, NOW() + interval '20 hours',"
						+ " NOW() + interval '20 hours 30 minutes', 'SCHEDULED', NOW(), NOW())",
						UUID.randomUUID(), ORG_A, BRANCH_A, SERVICE_A, STAFF_B))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	void v20BackfillsOperationalUsersAndLeavesCustomersUnassigned() {
		migrateToV20();

		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT user_id, organization_id, role, status"
						+ " FROM organization_membership ORDER BY role");
		assertThat(rows).hasSize(5);
		assertThat(rows).satisfiesExactlyInAnyOrder(
				row -> {
					assertThat(row.get("user_id")).isEqualTo(ADMIN_A);
					assertThat(row.get("role")).isEqualTo("ADMIN");
					assertThat(row.get("status")).isEqualTo("ACTIVE");
				},
				row -> {
					assertThat(row.get("user_id")).isEqualTo(MANAGER_A);
					assertThat(row.get("role")).isEqualTo("MANAGER");
					assertThat(row.get("status")).isEqualTo("ACTIVE");
				},
				row -> {
					assertThat(row.get("user_id")).isEqualTo(STAFF_A);
					assertThat(row.get("organization_id")).isEqualTo(ORG_A);
					assertThat(row.get("role")).isEqualTo("STAFF");
				},
				row -> {
					assertThat(row.get("user_id")).isEqualTo(STAFF_B);
					assertThat(row.get("organization_id")).isEqualTo(ORG_B);
					assertThat(row.get("role")).isEqualTo("STAFF");
				},
				row -> {
					// INACTIVE account → INACTIVE membership: no access, but
					// the grant is preserved for reactivation.
					assertThat(row.get("role")).isEqualTo("STAFF");
					assertThat(row.get("status")).isEqualTo("INACTIVE");
				});

		// The customer holds no membership and keeps its legacy column value.
		Integer customerMemberships = jdbc.queryForObject(
				"SELECT COUNT(*) FROM organization_membership m"
						+ " JOIN user_role r ON r.user_id = m.user_id"
						+ " WHERE m.user_id = ? AND r.role = 'CUSTOMER'",
				Integer.class, CUSTOMER_A);
		assertThat(customerMemberships).isZero();

		Integer legacyOrgStillPopulated = jdbc.queryForObject(
				"SELECT COUNT(*) FROM app_user WHERE id = ? AND organization_id IS NOT NULL",
				Integer.class, CUSTOMER_A);
		assertThat(legacyOrgStillPopulated).isEqualTo(1);
	}

	@Test
	void v20CollapsesMultiRoleLegacyUserToHighestPrivilege() {
		jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'MANAGER')", STAFF_A);
		jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'ADMIN')", STAFF_A);

		migrateToV20();

		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT role FROM organization_membership WHERE user_id = ?", STAFF_A);
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("role")).isEqualTo("ADMIN");
	}

	@Test
	void v20PreservesHistoricalTransactionsAndAllowsPlatformCustomers() {
		migrateToV20();

		// Historical rows keep their own organization/branch identity.
		Integer appointment = jdbc.queryForObject(
				"SELECT COUNT(*) FROM appointment WHERE customer_id = ?"
						+ " AND organization_id = ? AND branch_id = ?",
				Integer.class, CUSTOMER_A, ORG_A, BRANCH_A);
		assertThat(appointment).isEqualTo(1);
		Integer entry = jdbc.queryForObject(
				"SELECT COUNT(*) FROM queue_entry WHERE customer_id = ?"
						+ " AND organization_id = ?",
				Integer.class, CUSTOMER_A, ORG_A);
		assertThat(entry).isEqualTo(1);

		// The schema now supports the ADR-040 customer shape: an account
		// with no organization at all.
		UUID platformCustomer = UUID.randomUUID();
		jdbc.update("INSERT INTO app_user (id, organization_id, email, display_name, status,"
				+ " created_at, updated_at) VALUES (?, NULL, 'new@example.com', 'New',"
				+ " 'ACTIVE', NOW(), NOW())", platformCustomer);
		jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'CUSTOMER')",
				platformCustomer);

		// ...and that customer can be booked at an organization without any
		// membership in it (the composite FK is gone; the service-layer
		// authorization arrives in later 4R phases). A distinct start keeps
		// the per-service exclusion constraint satisfied.
		jdbc.update("INSERT INTO appointment (id, organization_id, branch_id, service_id,"
				+ " customer_id, start_at, end_at, status, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?, ?, NOW() + interval '50 hours',"
				+ " NOW() + interval '50 hours 30 minutes', 'SCHEDULED', NOW(), NOW())",
				UUID.randomUUID(), ORG_A, BRANCH_A, SERVICE_A, platformCustomer);

		Integer booked = jdbc.queryForObject(
				"SELECT COUNT(*) FROM appointment WHERE customer_id = ?",
				Integer.class, platformCustomer);
		assertThat(booked).isEqualTo(1);
	}
}
