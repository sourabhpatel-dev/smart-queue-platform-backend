package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * Genuine concurrency proofs for the queue engine (see ADR-012): a token
 * allocation race and two / three-counter claim races.
 *
 * <p>Deliberately NOT {@code @Transactional} — racing calls need their own
 * committed transactions, and nothing may roll back across threads. Each
 * test uses unique data inside this class's own container, so no cleanup is
 * needed. Own container per test class.
 */
@SpringBootTest
@Testcontainers
class QueueConcurrentTests {

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
	UserRepository users;

	@Autowired
	QueueJoinService joinService;

	@Autowired
	QueueCallService callService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	private int customerSequence = 0;

	@BeforeEach
	void clearTenantContext() {
		TenantContextHolder.clear();
	}

	private synchronized User newCustomer(Organization org, String tag) {
		customerSequence++;
		User customer = users.save(new User(org,
				"race-" + tag + "-" + customerSequence + "@example.com", "Racer"));
		customer.grantRole(RoleType.CUSTOMER);
		return customer;
	}

	private Queue queueWithCapability(String tag, Organization org, Branch branch,
			Service service, ServiceCounter counter) {
		capabilities.save(new CounterCapability(branch, service, counter));
		return queues.save(new Queue(org, branch, service));
	}

	private <T> List<T> race(int threads, Callable<T> attempt) throws Exception {
		CountDownLatch startGate = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					startGate.await(10, TimeUnit.SECONDS);
					return attempt.call();
				}));
			}
			startGate.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void concurrentJoinsReceiveUniqueSequentialTokens() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		Organization org = organizations.save(new Organization("Clinic " + tag));
		Branch branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		Service consultation = services.save(
				new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		Queue queue = queues.save(new Queue(org, branch, consultation));
		UUID queueId = queue.getId();

		int joins = 8;
		List<Integer> tokens = race(joins, () -> {
			User customer = newCustomer(org, tag);
			return joinService.join(queueId, customer.getId(), QueuePriority.NORMAL)
					.getTokenNumber();
		});

		assertThat(new HashSet<>(tokens)).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
		Integer rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM queue_entry WHERE queue_id = ?", Integer.class, queueId);
		assertThat(rows).isEqualTo(joins);
	}

	@Test
	void twoConcurrentCallersClaimDistinctEntries() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		Organization org = organizations.save(new Organization("Clinic " + tag));
		Branch branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		Service consultation = services.save(
				new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		ServiceCounter first = counters.save(new ServiceCounter(branch, "Counter 1"));
		ServiceCounter second = counters.save(new ServiceCounter(branch, "Counter 2"));
		Queue queue = queueWithCapability(tag, org, branch, consultation, first);
		capabilities.save(new CounterCapability(branch, consultation, second));
		joinService.join(queue.getId(), newCustomer(org, tag).getId(), QueuePriority.NORMAL);
		joinService.join(queue.getId(), newCustomer(org, tag).getId(), QueuePriority.NORMAL);
		UUID counterId = first.getId();
		UUID raceOrgId = org.getId();
		UUID raceBranchId = branch.getId();

		// Both racers hit the SAME counter: the purest double-claim scenario.
		// The tenant context is installed on each worker thread: ThreadLocal
		// scope never crosses into pool threads on its own.
		List<UUID> claimed = race(2, () -> {
			TenantContextHolder.set(new TenantContext(raceOrgId, raceBranchId));
			try {
				return callService.callNext(counterId).getId();
			} finally {
				TenantContextHolder.clear();
			}
		});

		assertThat(new HashSet<>(claimed)).hasSize(2);
		Integer waiting = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM queue_entry WHERE queue_id = ? AND status = 'WAITING'",
				Integer.class, queue.getId());
		assertThat(waiting).isZero();
	}

	@Test
	void threeConcurrentCallersClaimDistinctEntries() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		Organization org = organizations.save(new Organization("Clinic " + tag));
		Branch branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		Service consultation = services.save(
				new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		ServiceCounter first = counters.save(new ServiceCounter(branch, "Counter 1"));
		ServiceCounter second = counters.save(new ServiceCounter(branch, "Counter 2"));
		ServiceCounter third = counters.save(new ServiceCounter(branch, "Counter 3"));
		Queue queue = queueWithCapability(tag, org, branch, consultation, first);
		capabilities.save(new CounterCapability(branch, consultation, second));
		capabilities.save(new CounterCapability(branch, consultation, third));
		Set<UUID> entryIds = new HashSet<>();
		entryIds.add(joinService.join(queue.getId(), newCustomer(org, tag).getId(),
				QueuePriority.NORMAL).getId());
		entryIds.add(joinService.join(queue.getId(), newCustomer(org, tag).getId(),
				QueuePriority.NORMAL).getId());
		entryIds.add(joinService.join(queue.getId(), newCustomer(org, tag).getId(),
				QueuePriority.NORMAL).getId());
		List<UUID> counterIds = List.of(first.getId(), second.getId(), third.getId());
		AtomicInteger next = new AtomicInteger();
		UUID raceOrgId = org.getId();
		UUID raceBranchId = branch.getId();

		List<UUID> claimed = race(3, () -> {
			TenantContextHolder.set(new TenantContext(raceOrgId, raceBranchId));
			try {
				return callService.callNext(counterIds.get(next.getAndIncrement() % 3)).getId();
			} finally {
				TenantContextHolder.clear();
			}
		});

		assertThat(new HashSet<>(claimed)).isEqualTo(entryIds);
		Integer called = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM queue_entry WHERE queue_id = ? AND status = 'CALLED'",
				Integer.class, queue.getId());
		assertThat(called).isEqualTo(3);
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}
}
