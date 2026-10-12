package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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
import com.sourabh.smartqueue.queue.QueueEntryNotFoundException;
import com.sourabh.smartqueue.queue.QueueJoinService;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.QueueWaitTimeEstimator;
import com.sourabh.smartqueue.queue.WaitTimeEstimate;
import com.sourabh.smartqueue.queue.realtime.QueueRealtimeProjectionService;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.tenant.TenantContextRequiredException;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Cross-tenant isolation tests (see ADR-019): prove that organization A
 * cannot read or mutate organization B's data, and that branch-level
 * isolation works correctly. Each test rolls back. Own container per test
 * class.
 */
@SpringBootTest
@Testcontainers
@Transactional
class TenantIsolationTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Container
	@ServiceConnection
	static GenericContainer<?> redis =
			new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

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
	QueueWaitTimeEstimator estimator;

	@Autowired
	QueueRealtimeProjectionService projections;

	private Organization orgA;
	private Organization orgB;
	private Branch branchA;
	private Branch branchB;
	private Service serviceA;
	private Service serviceB;
	private ServiceCounter counterA;
	private ServiceCounter counterB;
	private Queue queueA;
	private Queue queueB;
	private User customerA;
	private User customerB;

	@BeforeEach
	void setUp() {
		orgA = organizations.save(new Organization("Org A"));
		orgB = organizations.save(new Organization("Org B"));
		branchA = branches.save(new Branch(orgA, "Branch A", "City", "Country", KOLKATA));
		branchB = branches.save(new Branch(orgB, "Branch B", "City", "Country", KOLKATA));
		serviceA = services.save(new Service(branchA, "Service A", Duration.ofMinutes(30)));
		serviceB = services.save(new Service(branchB, "Service B", Duration.ofMinutes(30)));
		counterA = counters.save(new ServiceCounter(branchA, "Counter A"));
		counterB = counters.save(new ServiceCounter(branchB, "Counter B"));
		capabilities.save(new CounterCapability(branchA, serviceA, counterA));
		capabilities.save(new CounterCapability(branchB, serviceB, counterB));
		queueA = queues.save(new Queue(orgA, branchA, serviceA));
		queueB = queues.save(new Queue(orgB, branchB, serviceB));
		customerA = users.save(new User(orgA, "a-" + UUID.randomUUID() + "@example.com", "A"));
		customerA.grantRole(RoleType.CUSTOMER);
		customerB = users.save(new User(orgB, "b-" + UUID.randomUUID() + "@example.com", "B"));
		customerB.grantRole(RoleType.CUSTOMER);
	}

	@AfterEach
	void clearContext() {
		TenantContextHolder.clear();
	}

	@Test
	void missingContextFailsClosedForCallNext() {
		assertThatThrownBy(() -> callService.callNext(counterA.getId()))
				.isInstanceOf(TenantContextRequiredException.class);
	}

	@Test
	void missingContextFailsClosedForComplete() {
		TenantContextHolder.set(new TenantContext(orgA.getId(), branchA.getId()));
		QueueEntry entry = joinService.join(queueA.getId(), customerA.getId(), QueuePriority.NORMAL);
		TenantContextHolder.clear();

		assertThatThrownBy(() -> lifecycle.complete(entry.getId()))
				.isInstanceOf(TenantContextRequiredException.class);
	}

	@Test
	void missingContextFailsClosedForEstimate() {
		TenantContextHolder.set(new TenantContext(orgA.getId(), branchA.getId()));
		QueueEntry entry = joinService.join(queueA.getId(), customerA.getId(), QueuePriority.NORMAL);
		TenantContextHolder.clear();

		assertThatThrownBy(() -> estimator.estimate(entry.getId()))
				.isInstanceOf(TenantContextRequiredException.class);
	}

	@Test
	void missingContextFailsClosedForProjection() {
		assertThatThrownBy(() -> projections.refresh(queueA.getId()))
				.isInstanceOf(TenantContextRequiredException.class);
	}

	@Test
	void wrongOrganizationCannotCallNext() {
		TenantContextHolder.set(new TenantContext(orgB.getId(), branchB.getId()));

		assertThatThrownBy(() -> callService.callNext(counterA.getId()))
				.isInstanceOf(com.sourabh.smartqueue.queue.InvalidQueueRequestException.class);
	}

	@Test
	void wrongOrganizationCannotComplete() {
		TenantContextHolder.set(new TenantContext(orgA.getId(), branchA.getId()));
		QueueEntry entry = joinService.join(queueA.getId(), customerA.getId(), QueuePriority.NORMAL);
		TenantContextHolder.set(new TenantContext(orgB.getId(), branchB.getId()));

		assertThatThrownBy(() -> lifecycle.complete(entry.getId()))
				.isInstanceOf(QueueEntryNotFoundException.class);
	}

	@Test
	void wrongOrganizationCannotEstimate() {
		TenantContextHolder.set(new TenantContext(orgA.getId(), branchA.getId()));
		QueueEntry entry = joinService.join(queueA.getId(), customerA.getId(), QueuePriority.NORMAL);
		TenantContextHolder.set(new TenantContext(orgB.getId(), branchB.getId()));

		assertThat(estimator.estimate(entry.getId())).isEmpty();
	}

	@Test
	void wrongOrganizationCannotReadProjection() {
		TenantContextHolder.set(new TenantContext(orgA.getId(), branchA.getId()));
		joinService.join(queueA.getId(), customerA.getId(), QueuePriority.NORMAL);
		TenantContextHolder.set(new TenantContext(orgB.getId(), branchB.getId()));

		assertThatThrownBy(() -> projections.refresh(queueA.getId()))
				.isInstanceOf(com.sourabh.smartqueue.queue.InvalidQueueRequestException.class);
	}

	@Test
	void wrongBranchCannotCallNext() {
		Branch branchA2 = branches.save(new Branch(orgA, "Branch A2", "City", "Country", KOLKATA));
		ServiceCounter counterA2 = counters.save(new ServiceCounter(branchA2, "Counter A2"));
		TenantContextHolder.set(new TenantContext(orgA.getId(), branchA2.getId()));

		assertThatThrownBy(() -> callService.callNext(counterA.getId()))
				.isInstanceOf(com.sourabh.smartqueue.queue.InvalidQueueRequestException.class);
	}

	@Test
	void correctOrganizationAndBranchSucceeds() {
		TenantContextHolder.set(new TenantContext(orgA.getId(), branchA.getId()));
		QueueEntry entry = joinService.join(queueA.getId(), customerA.getId(), QueuePriority.NORMAL);

		assertThat(entry).isNotNull();
		assertThat(entry.getQueue().getId()).isEqualTo(queueA.getId());
	}

	@Test
	void correctOrganizationLevelSucceeds() {
		TenantContextHolder.set(new TenantContext(orgA.getId(), null));
		QueueEntry entry = joinService.join(queueA.getId(), customerA.getId(), QueuePriority.NORMAL);

		assertThat(entry).isNotNull();
	}

	@Test
	void crossTenantQueueIdsAreIsolated() {
		TenantContextHolder.set(new TenantContext(orgA.getId(), branchA.getId()));
		joinService.join(queueA.getId(), customerA.getId(), QueuePriority.NORMAL);

		TenantContextHolder.set(new TenantContext(orgB.getId(), branchB.getId()));
		assertThatThrownBy(() -> projections.refresh(queueA.getId()))
				.isInstanceOf(com.sourabh.smartqueue.queue.InvalidQueueRequestException.class);
	}

	@Test
	void crossTenantEntryIdsAreIsolated() {
		TenantContextHolder.set(new TenantContext(orgA.getId(), branchA.getId()));
		QueueEntry entryA = joinService.join(queueA.getId(), customerA.getId(), QueuePriority.NORMAL);

		TenantContextHolder.set(new TenantContext(orgB.getId(), branchB.getId()));
		assertThatThrownBy(() -> lifecycle.cancel(entryA.getId()))
				.isInstanceOf(QueueEntryNotFoundException.class);
	}

	@Test
	void estimateReturnsEmptyForCrossTenantEntry() {
		TenantContextHolder.set(new TenantContext(orgA.getId(), branchA.getId()));
		QueueEntry entryA = joinService.join(queueA.getId(), customerA.getId(), QueuePriority.NORMAL);

		TenantContextHolder.set(new TenantContext(orgB.getId(), branchB.getId()));
		assertThat(estimator.estimate(entryA.getId())).isEmpty();
	}

	@Test
	void sameOrganizationDifferentBranchesAreIsolated() {
		Branch branchA2 = branches.save(new Branch(orgA, "Branch A2", "City", "Country", KOLKATA));
		Service serviceA2 = services.save(new Service(branchA2, "Service A2", Duration.ofMinutes(30)));
		Queue queueA2 = queues.save(new Queue(orgA, branchA2, serviceA2));

		TenantContextHolder.set(new TenantContext(orgA.getId(), branchA.getId()));
		joinService.join(queueA.getId(), customerA.getId(), QueuePriority.NORMAL);

		TenantContextHolder.set(new TenantContext(orgA.getId(), branchA2.getId()));
		assertThatThrownBy(() -> projections.refresh(queueA.getId()))
				.isInstanceOf(com.sourabh.smartqueue.queue.InvalidQueueRequestException.class);
	}

	@Test
	void contextIsClearedBetweenOperations() {
		TenantContextHolder.set(new TenantContext(orgA.getId(), branchA.getId()));
		QueueEntry entry = joinService.join(queueA.getId(), customerA.getId(), QueuePriority.NORMAL);
		TenantContextHolder.clear();

		assertThatThrownBy(() -> lifecycle.cancel(entry.getId()))
				.isInstanceOf(TenantContextRequiredException.class);
	}
}
