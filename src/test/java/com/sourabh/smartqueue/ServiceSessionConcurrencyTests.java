package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import com.sourabh.smartqueue.queue.QueueEntryLifecycleService;
import com.sourabh.smartqueue.queue.QueueEntryStateException;
import com.sourabh.smartqueue.queue.QueueEntryStatus;
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
 * Concurrency proofs for service sessions (see ADR-013): concurrent starts
 * and concurrent completions of the same entry.
 *
 * <p>Deliberately NOT {@code @Transactional} — racing calls need their own
 * committed transactions. Unique data per run inside this class's own
 * container, so no cleanup is needed. Own container per test class.
 */
@SpringBootTest
@Testcontainers
class ServiceSessionConcurrencyTests {

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
	QueueEntryLifecycleService lifecycle;

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

	private QueueEntry calledEntry(String tag, Organization org, Branch branch,
			Service service, ServiceCounter counter, Queue queue) {
		QueueEntry entry = joinService.join(queue.getId(),
				newCustomer(org, tag).getId(), QueuePriority.NORMAL);
		return callService.callNext(counter.getId());
	}

	@Test
	void concurrentStartsYieldExactlyOneSession() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		Organization org = organizations.save(new Organization("Clinic " + tag));
		Branch branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		Service consultation = services.save(
				new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		ServiceCounter counter = counters.save(new ServiceCounter(branch, "Counter 1"));
		capabilities.save(new CounterCapability(branch, consultation, counter));
		Queue queue = queues.save(new Queue(org, branch, consultation));
		TenantContextHolder.set(new TenantContext(org.getId(), branch.getId()));
		QueueEntry called = calledEntry(tag, org, branch, consultation, counter, queue);
		UUID entryId = called.getId();
		UUID counterId = counter.getId();

		AtomicInteger successes = new AtomicInteger();
		AtomicInteger rejections = new AtomicInteger();
		CountDownLatch startGate = new CountDownLatch(1);
		UUID raceOrgId = org.getId();
		UUID raceBranchId = branch.getId();
		Callable<Void> attempt = () -> {
			startGate.await(10, TimeUnit.SECONDS);
			TenantContextHolder.set(new TenantContext(raceOrgId, raceBranchId));
			try {
				lifecycle.startServing(entryId, counterId);
				successes.incrementAndGet();
			} catch (QueueEntryStateException e) {
				rejections.incrementAndGet();
			} finally {
				TenantContextHolder.clear();
			}
			return null;
		};

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<Void>> futures = new ArrayList<>();
			futures.add(pool.submit(attempt));
			futures.add(pool.submit(attempt));
			startGate.countDown();
			for (Future<Void> future : futures) {
				future.get(60, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(successes.get()).isEqualTo(1);
		assertThat(rejections.get()).isEqualTo(1);

		Integer sessions = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM service_session WHERE queue_entry_id = ?",
				Integer.class, entryId);
		assertThat(sessions).isEqualTo(1);
		String status = jdbcTemplate.queryForObject(
				"SELECT status FROM queue_entry WHERE id = ?", String.class, entryId);
		assertThat(status).isEqualTo("SERVING");
	}

	@Test
	void concurrentCompletionsYieldExactlyOneEnd() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		Organization org = organizations.save(new Organization("Clinic " + tag));
		Branch branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		Service consultation = services.save(
				new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		ServiceCounter counter = counters.save(new ServiceCounter(branch, "Counter 1"));
		capabilities.save(new CounterCapability(branch, consultation, counter));
		Queue queue = queues.save(new Queue(org, branch, consultation));
		TenantContextHolder.set(new TenantContext(org.getId(), branch.getId()));
		QueueEntry called = calledEntry(tag, org, branch, consultation, counter, queue);
		lifecycle.startServing(called.getId(), counter.getId());
		UUID entryId = called.getId();

		AtomicReference<RuntimeException> unexpected = new AtomicReference<>();
		AtomicInteger successes = new AtomicInteger();
		AtomicInteger rejections = new AtomicInteger();
		CountDownLatch startGate = new CountDownLatch(1);
		UUID raceOrgId = org.getId();
		UUID raceBranchId = branch.getId();
		Callable<Void> attempt = () -> {
			startGate.await(10, TimeUnit.SECONDS);
			TenantContextHolder.set(new TenantContext(raceOrgId, raceBranchId));
			try {
				lifecycle.complete(entryId);
				successes.incrementAndGet();
			} catch (QueueEntryStateException e) {
				rejections.incrementAndGet();
			} catch (RuntimeException e) {
				unexpected.compareAndSet(null, e);
				throw e;
			} finally {
				TenantContextHolder.clear();
			}
			return null;
		};

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<Void>> futures = new ArrayList<>();
			futures.add(pool.submit(attempt));
			futures.add(pool.submit(attempt));
			startGate.countDown();
			for (Future<Void> future : futures) {
				future.get(60, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(unexpected.get()).isNull();
		assertThat(successes.get()).isEqualTo(1);
		assertThat(rejections.get()).isEqualTo(1);

		String status = jdbcTemplate.queryForObject(
				"SELECT status FROM queue_entry WHERE id = ?", String.class, entryId);
		assertThat(status).isEqualTo("COMPLETED");
		Integer openSessions = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM service_session WHERE queue_entry_id = ? AND ended_at IS NULL",
				Integer.class, entryId);
		assertThat(openSessions).isZero();
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}
}
