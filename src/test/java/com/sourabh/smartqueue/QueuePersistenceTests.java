package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.sourabh.smartqueue.queue.CounterCannotServeQueueException;
import com.sourabh.smartqueue.queue.InvalidQueueRequestException;
import com.sourabh.smartqueue.queue.NoWaitingQueueEntryException;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueCallService;
import com.sourabh.smartqueue.queue.QueueEntry;
import com.sourabh.smartqueue.queue.QueueEntryLifecycleService;
import com.sourabh.smartqueue.queue.QueueEntryNotFoundException;
import com.sourabh.smartqueue.queue.QueueEntryRepository;
import com.sourabh.smartqueue.queue.QueueEntryStateException;
import com.sourabh.smartqueue.queue.QueueEntryStatus;
import com.sourabh.smartqueue.queue.QueueJoinService;
import com.sourabh.smartqueue.queue.QueueNotActiveException;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.QueueStatus;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Phase 2F queue tests: persistence, tokens, ordering, capabilities,
 * lifecycle and database integrity against real PostgreSQL (see ADR-012).
 * Each test rolls back. Own container per test class.
 */
@SpringBootTest
@Testcontainers
@Transactional
class QueuePersistenceTests {

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
	QueueEntryLifecycleService lifecycle;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Flyway flyway;

	Organization org;
	Branch branch;
	Service consultation;
	ServiceCounter counter1;
	Queue queue;

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

	private int customerSequence = 0;

	private User newCustomer() {
		customerSequence++;
		User customer = users.save(new User(org,
				"walkin" + customerSequence + "@example.com", "Walk-in " + customerSequence));
		customer.grantRole(RoleType.CUSTOMER);
		return customer;
	}

	private QueueEntry join(Queue q, User customer, QueuePriority priority) {
		return joinService.join(q.getId(), customer.getId(), priority);
	}

	// ---- Queue persistence ----

	@Test
	void persistsQueueWithDefaults() {
		assertThat(queue.getId()).isNotNull();
		assertThat(queue.getStatus()).isEqualTo(QueueStatus.ACTIVE);

		Queue reloaded = queues.findById(queue.getId()).orElseThrow();
		assertThat(reloaded.getBranch().getId()).isEqualTo(branch.getId());
		assertThat(reloaded.getService().getId()).isEqualTo(consultation.getId());
		assertThat(reloaded.getOrganization().getId()).isEqualTo(org.getId());
	}

	@Test
	void queueStatusPersistsAsString() {
		queue.setStatus(QueueStatus.INACTIVE);
		queues.saveAndFlush(queue);

		String stored = jdbcTemplate.queryForObject(
				"SELECT status FROM queue WHERE id = ?", String.class, queue.getId());
		assertThat(stored).isEqualTo("INACTIVE");
	}

	@Test
	void sameServiceHasQueuesInDifferentBranches() {
		Branch uptown = branches.save(new Branch(org, "Uptown", "Bhopal", "India", KOLKATA));
		Service uptownService =
				services.save(new Service(uptown, "General Consultation", Duration.ofMinutes(30)));

		Queue other = queues.save(new Queue(org, uptown, uptownService));

		assertThat(other.getId()).isNotNull();
	}

	@Test
	void duplicateBranchServiceQueueIsRejected() {
		assertThatThrownBy(() -> queues.saveAndFlush(new Queue(org, branch, consultation)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void crossBranchQueueServiceIsRejectedByDomainGuard() {
		Branch uptown = branches.save(new Branch(org, "Uptown", "Bhopal", "India", KOLKATA));
		Service uptownService =
				services.save(new Service(uptown, "General Consultation", Duration.ofMinutes(30)));

		assertThatThrownBy(() -> new Queue(org, branch, uptownService))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void crossBranchQueueServiceIsRejectedByDatabase() {
		Branch uptown = branches.save(new Branch(org, "Uptown", "Bhopal", "India", KOLKATA));
		Service uptownService =
				services.save(new Service(uptown, "General Consultation", Duration.ofMinutes(30)));

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO queue (id, organization_id, branch_id, service_id,"
						+ " status, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
				UUID.randomUUID(), org.getId(), branch.getId(), uptownService.getId(), "ACTIVE"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ---- QueueEntry persistence ----

	@Test
	void persistsWalkInEntryWithToken() {
		QueueEntry saved = join(queue, newCustomer(), QueuePriority.NORMAL);

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getTokenNumber()).isEqualTo(1);
		assertThat(saved.getBusinessDate())
				.isEqualTo(Instant.now().atZone(KOLKATA).toLocalDate());
		assertThat(saved.getPriority()).isEqualTo(QueuePriority.NORMAL);
		assertThat(saved.getStatus()).isEqualTo(QueueEntryStatus.WAITING);
		assertThat(saved.getJoinedAt()).isNotNull();
		assertThat(saved.getCalledAt()).isNull();

		QueueEntry reloaded = entries.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getCustomer().getId()).isNotNull();
		assertThat(reloaded.getQueue().getId()).isEqualTo(queue.getId());
	}

	@Test
	void tokensAreSequentialPerQueueAndDate() {
		QueueEntry first = join(queue, newCustomer(), QueuePriority.NORMAL);
		QueueEntry second = join(queue, newCustomer(), QueuePriority.NORMAL);
		QueueEntry third = join(queue, newCustomer(), QueuePriority.NORMAL);

		assertThat(first.getTokenNumber()).isEqualTo(1);
		assertThat(second.getTokenNumber()).isEqualTo(2);
		assertThat(third.getTokenNumber()).isEqualTo(3);
	}

	@Test
	void tokensAreScopedPerQueue() {
		Branch uptown = branches.save(new Branch(org, "Uptown", "Bhopal", "India", KOLKATA));
		Service uptownService =
				services.save(new Service(uptown, "General Consultation", Duration.ofMinutes(30)));
		Queue other = queues.save(new Queue(org, uptown, uptownService));
		join(queue, newCustomer(), QueuePriority.NORMAL);

		QueueEntry elsewhere = join(other, newCustomer(), QueuePriority.NORMAL);

		assertThat(elsewhere.getTokenNumber()).isEqualTo(1);
	}

	@Test
	void priorityAndStatusPersistAsStrings() {
		QueueEntry saved = join(queue, newCustomer(), QueuePriority.EMERGENCY);

		String priority = jdbcTemplate.queryForObject(
				"SELECT priority FROM queue_entry WHERE id = ?", String.class, saved.getId());
		String status = jdbcTemplate.queryForObject(
				"SELECT status FROM queue_entry WHERE id = ?", String.class, saved.getId());
		assertThat(priority).isEqualTo("EMERGENCY");
		assertThat(status).isEqualTo("WAITING");
	}

	@Test
	void customerJoinsQueueAtAnotherOrganization() {
		// ADR-040: a customer (CUSTOMER-only role) joins any ACTIVE queue —
		// the entry records the queue's organization, which stays pinned by
		// the queue leg. Operational customers keep the same-organization
		// pin (a silent cross-org role grant must fail fast, not leak).
		Organization other = organizations.save(new Organization("County Hospital"));
		User outsider = users.save(new User(other, "outsider@example.com", "Outsider"));
		outsider.grantRole(RoleType.CUSTOMER);
		outsider = users.save(outsider);

		QueueEntry entry = new QueueEntry(org, queue, outsider, 99,
				LocalDate.of(2026, 10, 6), QueuePriority.NORMAL);

		assertThat(entry.getOrganization().getId()).isEqualTo(org.getId());
		User staff = users.save(new User(other, "staff-x@example.com", "Other Staff"));
		staff.grantRole(RoleType.STAFF);
		assertThatThrownBy(() -> new QueueEntry(org, queue, staff, 99,
				LocalDate.of(2026, 10, 6), QueuePriority.NORMAL))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/**
	 * V20 replaced the customer↔organization composite with a plain
	 * customer FK (ADR-040): a foreign-organization customer is no longer
	 * rejected by the database, but a dangling customer still is — and
	 * the queue leg stays pinned to its organization below.
	 */
	@Test
	void customerLegKeepsReferentialIntegrity() {
		Organization other = organizations.save(new Organization("County Hospital"));
		User outsider = users.save(new User(other, "outsider@example.com", "Outsider"));
		outsider.grantRole(RoleType.CUSTOMER);
		outsider = users.save(outsider);
		// Flush the JPA writes before the raw-SQL assertions below —
		// JdbcTemplate does not trigger the persistence-context flush.
		organizations.flush();
		users.flush();

		Integer inserted = jdbcTemplate.update(
				"INSERT INTO queue_entry (id, organization_id, queue_id, customer_id,"
						+ " token_number, business_date, priority, status,"
						+ " joined_at, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), NOW())",
				UUID.randomUUID(), org.getId(), queue.getId(), outsider.getId(),
				1, LocalDate.of(2026, 10, 6), "NORMAL", "WAITING");
		assertThat(inserted).isEqualTo(1);

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO queue_entry (id, organization_id, queue_id, customer_id,"
						+ " token_number, business_date, priority, status,"
						+ " joined_at, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), NOW())",
				UUID.randomUUID(), org.getId(), queue.getId(), UUID.randomUUID(),
				1, LocalDate.of(2026, 10, 6), "NORMAL", "WAITING"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void duplicateActiveEntryIsRejectedByDatabase() {
		User customer = newCustomer();
		join(queue, customer, QueuePriority.NORMAL);

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO queue_entry (id, organization_id, queue_id, customer_id,"
						+ " token_number, business_date, priority, status,"
						+ " joined_at, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), NOW())",
				UUID.randomUUID(), org.getId(), queue.getId(), customer.getId(),
				99, LocalDate.of(2026, 10, 6), "NORMAL", "WAITING"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void completedEntryAllowsRejoining() {
		User customer = newCustomer();
		QueueEntry first = join(queue, customer, QueuePriority.NORMAL);
		lifecycle.cancel(first.getId());

		QueueEntry again = join(queue, customer, QueuePriority.PRIORITY);

		assertThat(again.getId()).isNotNull();
		assertThat(again.getTokenNumber()).isEqualTo(2);
	}

	@Test
	void joinUnknownQueueIsRejected() {
		assertThatThrownBy(() -> joinService.join(UUID.randomUUID(), newCustomer().getId(),
				QueuePriority.NORMAL))
				.isInstanceOf(InvalidQueueRequestException.class);
	}

	@Test
	void joinInactiveQueueIsRejected() {
		queue.setStatus(QueueStatus.INACTIVE);

		assertThatThrownBy(() -> join(queue, newCustomer(), QueuePriority.NORMAL))
				.isInstanceOf(QueueNotActiveException.class);
	}

	// ---- Ordering ----

	@Test
	void emergencyBeatsPriorityBeatsNormal() {
		join(queue, newCustomer(), QueuePriority.NORMAL);
		join(queue, newCustomer(), QueuePriority.EMERGENCY);
		join(queue, newCustomer(), QueuePriority.PRIORITY);

		assertThat(callService.callNext(counter1.getId()).getPriority())
				.isEqualTo(QueuePriority.EMERGENCY);
		assertThat(callService.callNext(counter1.getId()).getPriority())
				.isEqualTo(QueuePriority.PRIORITY);
		assertThat(callService.callNext(counter1.getId()).getPriority())
				.isEqualTo(QueuePriority.NORMAL);
	}

	@Test
	void fifoWithinSamePriority() {
		QueueEntry first = join(queue, newCustomer(), QueuePriority.NORMAL);
		join(queue, newCustomer(), QueuePriority.NORMAL);

		QueueEntry called = callService.callNext(counter1.getId());

		assertThat(called.getId()).isEqualTo(first.getId());
	}

	// ---- Counter capability ----

	@Test
	void counterWithoutCapabilityCannotCall() {
		ServiceCounter pharmacyCounter = counters.save(new ServiceCounter(branch, "Counter 9"));
		join(queue, newCustomer(), QueuePriority.NORMAL);

		assertThatThrownBy(() -> callService.callNext(pharmacyCounter.getId()))
				.isInstanceOf(CounterCannotServeQueueException.class);
	}

	@Test
	void counterServingOtherServiceFindsNothingWaiting() {
		Service pharmacy = services.save(new Service(branch, "Pharmacy", Duration.ofMinutes(10)));
		ServiceCounter pharmacyCounter = counters.save(new ServiceCounter(branch, "Counter 9"));
		capabilities.save(new CounterCapability(branch, pharmacy, pharmacyCounter));
		join(queue, newCustomer(), QueuePriority.NORMAL);

		assertThatThrownBy(() -> callService.callNext(pharmacyCounter.getId()))
				.isInstanceOf(NoWaitingQueueEntryException.class);
	}

	@Test
	void inactiveCounterCannotCall() {
		counter1.setStatus(CounterStatus.MAINTENANCE);
		join(queue, newCustomer(), QueuePriority.NORMAL);

		assertThatThrownBy(() -> callService.callNext(counter1.getId()))
				.isInstanceOf(CounterCannotServeQueueException.class);
	}

	@Test
	void callNextWithNoWaitingEntriesThrows() {
		assertThatThrownBy(() -> callService.callNext(counter1.getId()))
				.isInstanceOf(NoWaitingQueueEntryException.class);
	}

	@Test
	void callNextUnknownCounterIsRejected() {
		assertThatThrownBy(() -> callService.callNext(UUID.randomUUID()))
				.isInstanceOf(InvalidQueueRequestException.class);
	}

	@Test
	void crossBranchCounterFindsNothingWaiting() {
		Branch uptown = branches.save(new Branch(org, "Uptown", "Bhopal", "India", KOLKATA));
		Service uptownService =
				services.save(new Service(uptown, "General Consultation", Duration.ofMinutes(30)));
		ServiceCounter uptownCounter = counters.save(new ServiceCounter(uptown, "Counter 1"));
		capabilities.save(new CounterCapability(uptown, uptownService, uptownCounter));
		join(queue, newCustomer(), QueuePriority.NORMAL);
		// Entries wait in Downtown; the Uptown counter is outside the
		// tenant scope and must not see them.
		assertThatThrownBy(() ->
				callService.callNext(uptownCounter.getId()))
				.isInstanceOf(InvalidQueueRequestException.class);
	}

	// ---- Lifecycle ----

	@Test
	void fullLifecycleChain() {
		QueueEntry entry = join(queue, newCustomer(), QueuePriority.NORMAL);

		QueueEntry called = lifecycle.startServing(
				callService.callNext(counter1.getId()).getId(), counter1.getId());
		assertThat(called.getStatus()).isEqualTo(QueueEntryStatus.SERVING);

		QueueEntry completed = lifecycle.complete(entry.getId());
		assertThat(completed.getStatus()).isEqualTo(QueueEntryStatus.COMPLETED);
		assertThat(completed.getCompletedAt()).isNotNull();
		assertThat(completed.getCalledAt()).isNotNull();
	}

	@Test
	void cancelFromWaitingAndCalled() {
		QueueEntry waiting = join(queue, newCustomer(), QueuePriority.NORMAL);
		QueueEntry toCall = join(queue, newCustomer(), QueuePriority.NORMAL);
		callService.callNext(counter1.getId());

		assertThat(lifecycle.cancel(waiting.getId()).getStatus())
				.isEqualTo(QueueEntryStatus.CANCELLED);
		assertThat(lifecycle.cancel(toCall.getId()).getStatus())
				.isEqualTo(QueueEntryStatus.CANCELLED);
	}

	@Test
	void noShowFromWaitingAndCalled() {
		QueueEntry waiting = join(queue, newCustomer(), QueuePriority.NORMAL);
		QueueEntry toCall = join(queue, newCustomer(), QueuePriority.NORMAL);
		callService.callNext(counter1.getId());

		assertThat(lifecycle.markNoShow(waiting.getId()).getStatus())
				.isEqualTo(QueueEntryStatus.NO_SHOW);
		assertThat(lifecycle.markNoShow(toCall.getId()).getStatus())
				.isEqualTo(QueueEntryStatus.NO_SHOW);
	}

	@Test
	void invalidTransitionsFail() {
		QueueEntry entry = join(queue, newCustomer(), QueuePriority.NORMAL);

		// SERVING/COMPLETED moves from WAITING are illegal.
		assertThatThrownBy(() -> lifecycle.startServing(entry.getId(), counter1.getId()))
				.isInstanceOf(QueueEntryStateException.class);
		assertThatThrownBy(() -> lifecycle.complete(entry.getId()))
				.isInstanceOf(QueueEntryStateException.class);

		// Terminal states accept nothing further.
		lifecycle.cancel(entry.getId());
		assertThatThrownBy(() -> lifecycle.cancel(entry.getId()))
				.isInstanceOf(QueueEntryStateException.class);
		assertThatThrownBy(() -> lifecycle.startServing(entry.getId(), counter1.getId()))
				.isInstanceOf(QueueEntryStateException.class);
		assertThatThrownBy(() -> lifecycle.markNoShow(entry.getId()))
				.isInstanceOf(QueueEntryStateException.class);
	}

	@Test
	void unknownEntryIdIsRejected() {
		UUID ghost = UUID.randomUUID();

		assertThatThrownBy(() -> lifecycle.startServing(ghost, counter1.getId()))
				.isInstanceOf(QueueEntryNotFoundException.class);
		assertThatThrownBy(() -> lifecycle.cancel(ghost))
				.isInstanceOf(QueueEntryNotFoundException.class);
	}

	@Test
	void flywayAppliedV7DomainMigration() {
		assertThat(flyway.info().applied())
				.anySatisfy(applied -> assertThat(applied.getVersion().getVersion()).isEqualTo("7"));

		Integer tables = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables"
						+ " WHERE table_schema = 'public'"
						+ " AND table_name IN ('queue', 'queue_entry', 'queue_token_counter')",
				Integer.class);
		assertThat(tables).isEqualTo(3);
	}
}
