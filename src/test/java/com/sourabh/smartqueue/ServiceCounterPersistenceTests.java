package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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
import com.sourabh.smartqueue.counter.CounterCapability;
import com.sourabh.smartqueue.counter.CounterCapabilityRepository;
import com.sourabh.smartqueue.counter.CounterStatus;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.service.ServiceStatus;

/**
 * Phase 2B domain tests: Service, ServiceCounter and their capability
 * relationship against real PostgreSQL (see ADR-008). Each test rolls back.
 *
 * <p>Own container per test class (see the note in
 * {@code PostgresInfrastructureTests} on why it is not inherited).
 */
@SpringBootTest
@Testcontainers
@Transactional
class ServiceCounterPersistenceTests {

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
	ServiceCounterRepository counters;

	@Autowired
	CounterCapabilityRepository capabilities;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Flyway flyway;

	private Branch newBranch(String orgName, String branchName) {
		Organization org = organizations.save(new Organization(orgName));
		return branches.save(new Branch(org, branchName, "Springfield", "India", ZoneId.of("Asia/Kolkata")));
	}

	private Service newService(Branch branch, String name) {
		return services.save(new Service(branch, name, Duration.ofMinutes(30)));
	}

	private ServiceCounter newCounter(Branch branch, String name) {
		return counters.save(new ServiceCounter(branch, name));
	}

	@Test
	void persistsServiceWithDefaults() {
		Branch branch = newBranch("City Clinic", "Downtown");
		Service saved = newService(branch, "General Consultation");

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getStatus()).isEqualTo(ServiceStatus.ACTIVE);
		assertThat(saved.getStandardDuration()).isEqualTo(Duration.ofMinutes(30));
		assertThat(saved.getCreatedAt()).isNotNull();

		Service reloaded = services.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getBranch().getId()).isEqualTo(branch.getId());
	}

	@Test
	void serviceStatusAndDurationPersistCorrectly() {
		Branch branch = newBranch("City Clinic", "Downtown");
		Service service = newService(branch, "Blood Test");
		service.setStatus(ServiceStatus.INACTIVE);
		service.setDescription("Fasting sample required");
		services.saveAndFlush(service);

		String storedStatus = jdbcTemplate.queryForObject(
				"SELECT status FROM service WHERE id = ?", String.class, service.getId());
		Integer storedMinutes = jdbcTemplate.queryForObject(
				"SELECT standard_duration_minutes FROM service WHERE id = ?", Integer.class,
				service.getId());
		assertThat(storedStatus).isEqualTo("INACTIVE");
		assertThat(storedMinutes).isEqualTo(30);

		Service reloaded = services.findById(service.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(ServiceStatus.INACTIVE);
		assertThat(reloaded.getStandardDuration()).isEqualTo(Duration.ofMinutes(30));
		assertThat(reloaded.getDescription()).isEqualTo("Fasting sample required");
	}

	@Test
	void rejectsNonPositiveServiceDuration() {
		Branch branch = newBranch("City Clinic", "Downtown");

		assertThatThrownBy(() -> new Service(branch, "Zero", Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Service(branch, "Negative", Duration.ofMinutes(-5)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Service(branch, "Seconds", Duration.ofSeconds(30)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void sameServiceNameInDifferentBranches() {
		Branch first = newBranch("City Clinic", "Downtown");
		Branch second = newBranch("City Clinic", "Uptown");
		newService(first, "General Consultation");

		Service sameNameElsewhere = newService(second, "General Consultation");

		assertThat(sameNameElsewhere.getId()).isNotNull();
	}

	@Test
	void duplicateServiceNameWithinBranchIsRejected() {
		Branch branch = newBranch("City Clinic", "Downtown");
		newService(branch, "General Consultation");

		assertThatThrownBy(() -> services.saveAndFlush(newService(branch, "General Consultation")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void persistsCounterWithDefaults() {
		Branch branch = newBranch("City Clinic", "Downtown");
		ServiceCounter saved = newCounter(branch, "Counter 1");

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getStatus()).isEqualTo(CounterStatus.ACTIVE);

		ServiceCounter reloaded = counters.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getBranch().getId()).isEqualTo(branch.getId());
	}

	@Test
	void counterStatusPersistsAsString() {
		Branch branch = newBranch("City Clinic", "Downtown");
		ServiceCounter counter = newCounter(branch, "Counter 1");
		counter.setStatus(CounterStatus.MAINTENANCE);
		counters.saveAndFlush(counter);

		String stored = jdbcTemplate.queryForObject(
				"SELECT status FROM service_counter WHERE id = ?", String.class, counter.getId());
		assertThat(stored).isEqualTo("MAINTENANCE");
		assertThat(counters.findById(counter.getId()).orElseThrow().getStatus())
				.isEqualTo(CounterStatus.MAINTENANCE);
	}

	@Test
	void sameCounterNameInDifferentBranches() {
		Branch first = newBranch("City Clinic", "Downtown");
		Branch second = newBranch("City Clinic", "Uptown");
		newCounter(first, "Counter 1");

		ServiceCounter sameNameElsewhere = newCounter(second, "Counter 1");

		assertThat(sameNameElsewhere.getId()).isNotNull();
	}

	@Test
	void duplicateCounterNameWithinBranchIsRejected() {
		Branch branch = newBranch("City Clinic", "Downtown");
		newCounter(branch, "Counter 1");

		assertThatThrownBy(() -> counters.saveAndFlush(newCounter(branch, "Counter 1")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void counterSupportsMultipleServices() {
		Branch branch = newBranch("City Clinic", "Downtown");
		Service consultation = newService(branch, "General Consultation");
		Service bloodTest = newService(branch, "Blood Test");
		ServiceCounter counter = newCounter(branch, "Counter 2");

		capabilities.save(new CounterCapability(branch, consultation, counter));
		capabilities.save(new CounterCapability(branch, bloodTest, counter));

		assertThat(capabilities.findByCounter(counter)).hasSize(2);
	}

	@Test
	void serviceSupportedByMultipleCounters() {
		Branch branch = newBranch("City Clinic", "Downtown");
		Service consultation = newService(branch, "General Consultation");
		ServiceCounter first = newCounter(branch, "Counter 1");
		ServiceCounter second = newCounter(branch, "Counter 2");

		capabilities.save(new CounterCapability(branch, consultation, first));
		capabilities.save(new CounterCapability(branch, consultation, second));

		assertThat(capabilities.findByService(consultation)).hasSize(2);
	}

	@Test
	void duplicateServiceCounterAssociationIsRejected() {
		Branch branch = newBranch("City Clinic", "Downtown");
		Service consultation = newService(branch, "General Consultation");
		ServiceCounter counter = newCounter(branch, "Counter 1");
		capabilities.save(new CounterCapability(branch, consultation, counter));

		assertThatThrownBy(() -> capabilities
				.saveAndFlush(new CounterCapability(branch, consultation, counter)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void crossBranchAssociationIsRejectedByDomainGuard() {
		Branch first = newBranch("City Clinic", "Downtown");
		Branch second = newBranch("City Clinic", "Uptown");
		Service consultation = newService(first, "General Consultation");
		ServiceCounter counter = newCounter(second, "Counter 1");

		assertThatThrownBy(() -> new CounterCapability(first, consultation, counter))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void crossBranchAssociationIsRejectedByDatabase() {
		Branch first = newBranch("City Clinic", "Downtown");
		Branch second = newBranch("City Clinic", "Uptown");
		Service consultation = newService(first, "General Consultation");
		ServiceCounter counter = newCounter(second, "Counter 1");

		// Bypass the domain guard with raw SQL: the composite foreign keys
		// must still reject a Branch-1 service linked under Branch-2's counter.
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO counter_capability"
						+ " (id, branch_id, service_id, counter_id, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, NOW(), NOW())",
				UUID.randomUUID(), first.getId(), consultation.getId(), counter.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void flywayAppliedV3DomainMigration() {
		assertThat(flyway.info().applied())
				.anySatisfy(applied -> assertThat(applied.getVersion().getVersion()).isEqualTo("3"));

		Integer tables = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables"
						+ " WHERE table_schema = 'public'"
						+ " AND table_name IN ('service', 'service_counter', 'counter_capability')",
				Integer.class);
		assertThat(tables).isEqualTo(3);
	}
}
