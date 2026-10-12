package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Phase 1 infrastructure test: proves the application context starts against
 * a real PostgreSQL-compatible database with Flyway migrations applied and
 * JPA initialized. Assertions stay at the infrastructure level
 * (connectivity, migration history, JPA bootstrap); domain behavior lives
 * in the feature test classes.
 *
 * <p>Note: the container field lives in this class, not in a shared
 * superclass. A {@code static} container declared in a common base is a
 * single instance whose lifecycle every subclass fights over — one class
 * stopping it breaks the others mid-run.
 */
@SpringBootTest
@Testcontainers
class PostgresInfrastructureTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Flyway flyway;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@Test
	void applicationContextLoads() {
		assertThat(dataSource).isNotNull();
		assertThat(entityManagerFactory).isNotNull();
	}

	@Test
	void canReachPostgreSQL() {
		Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
		assertThat(result).isEqualTo(1);
	}

	@Test
	void flywayAppliedBaselineMigration() {
		assertThat(flyway.info().applied())
				.anySatisfy(applied -> assertThat(applied.getVersion().getVersion()).isEqualTo("1"));

		Integer appliedCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class);
		assertThat(appliedCount).isGreaterThanOrEqualTo(1);
	}

	@Test
	void jpaMapsDomainEntities() {
		// Phase 2A maps Organization + Branch; bootstrap must include them.
		assertThat(entityManagerFactory.getMetamodel().getEntities())
				.anySatisfy(e -> assertThat(e.getName()).isEqualTo("Organization"))
				.anySatisfy(e -> assertThat(e.getName()).isEqualTo("Branch"));
		assertThat(entityManagerFactory.createEntityManager()).isNotNull();
	}
}
