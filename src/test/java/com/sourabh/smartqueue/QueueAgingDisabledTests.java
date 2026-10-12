package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.counter.CounterCapability;
import com.sourabh.smartqueue.counter.CounterCapabilityRepository;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueCallService;
import com.sourabh.smartqueue.queue.QueueEntry;
import com.sourabh.smartqueue.queue.QueueEntryRepository;
import com.sourabh.smartqueue.queue.QueueJoinService;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Aging-disabled behavior (see ADR-014): with
 * {@code queue.aging.enabled=false} the claim reproduces the Phase 2F
 * strict order exactly, however long entries have waited.
 */
@SpringBootTest
@Testcontainers
@Transactional
@TestPropertySource(properties = "queue.aging.enabled=false")
class QueueAgingDisabledTests {

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
	ServiceCounterRepository counters;

	@Autowired
	CounterCapabilityRepository capabilities;

	@Autowired
	QueueRepository queues;

	@Autowired
	QueueEntryRepository entries;

	@Autowired
	UserRepository users;

	@Autowired
	QueueJoinService joinService;

	@Autowired
	QueueCallService callService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	Organization org;
	Branch branch;
	Service consultation;
	ServiceCounter counter1;
	Queue queue;

	private int customerSequence = 0;

	@BeforeEach
	void setUp() {
		org = organizations.save(new Organization("City Clinic"));
		branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		consultation = services.save(new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		counter1 = counters.save(new ServiceCounter(branch, "Counter 1"));
		capabilities.save(new CounterCapability(branch, consultation, counter1));
		queue = queues.save(new Queue(org, branch, consultation));
		TenantContextHolder.set(new TenantContext(org.getId(), branch.getId()));
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private QueueEntry joinAged(QueuePriority priority, long minutesAgo, long agoBaseEpochSecond) {
		User customer = users.save(new User(org,
				"disabled" + (++customerSequence) + "@example.com", "Disabled"));
		customer.grantRole(RoleType.CUSTOMER);
		QueueEntry entry = joinService.join(queue.getId(), customer.getId(), priority);
		jdbcTemplate.update("UPDATE queue_entry SET joined_at = ? WHERE id = ?",
				Timestamp.from(java.time.Instant.ofEpochSecond(
						agoBaseEpochSecond - minutesAgo * 60)),
				entry.getId());
		return entry;
	}

	@Test
	void disabledIgnoresWaitingTime() {
		long nowEpoch = java.time.Instant.now().getEpochSecond();
		joinAged(QueuePriority.NORMAL, 600, nowEpoch);
		QueueEntry freshPriority = joinAged(QueuePriority.PRIORITY, 0, nowEpoch);
		entries.flush();

		assertThat(callService.callNext(counter1.getId()).getId())
				.isEqualTo(freshPriority.getId());
	}

	@Test
	void disabledKeepsEmergencyFirst() {
		long nowEpoch = java.time.Instant.now().getEpochSecond();
		joinAged(QueuePriority.NORMAL, 600, nowEpoch);
		QueueEntry emergency = joinAged(QueuePriority.EMERGENCY, 0, nowEpoch);
		entries.flush();

		assertThat(callService.callNext(counter1.getId()).getId())
				.isEqualTo(emergency.getId());
	}
}
