package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.counter.CounterCapability;
import com.sourabh.smartqueue.counter.CounterCapabilityRepository;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.infrastructure.redis.RedisJson;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueJoinService;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.realtime.QueueRealtimeDestinations;
import com.sourabh.smartqueue.queue.realtime.QueueRealtimeState;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Phase 3C WebSocket tests, authenticated since Phase 3F (see ADR-021):
 * every STOMP session CONNECTs with a bearer token minted through the real
 * login flow. Non-transactional so business commits (and their
 * AFTER_COMMIT broadcasts) really happen; unique data per run inside this
 * class's own containers. Bounded future-takes synchronize on messages —
 * never sleeps; absence assertions use short documented bounds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class QueueRealtimeWebSocketTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Container
	@ServiceConnection
	static GenericContainer<?> redis =
			new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

	@LocalServerPort
	int port;

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
	OrganizationMembershipRepository memberships;

	@Autowired
	com.sourabh.smartqueue.staff.StaffAssignmentRepository assignments;

	@Autowired
	QueueJoinService joinService;

	@Autowired
	PasswordCredentialService credentials;

	@Autowired
	AuthService authService;

	@Autowired
	RedisJson redisJson;

	private final List<String> received = new CopyOnWriteArrayList<>();

	static final String FIXTURE_PASSWORD = "Ws-Fixture-Password-123";

	@BeforeEach
	void clearReceived() {
		received.clear();
	}

	private Fixture fixture(String tag) {
		Organization org = organizations.save(new Organization("Clinic " + tag));
		Branch branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		Service consultation = services.save(
				new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		ServiceCounter counter = counters.save(new ServiceCounter(branch, "Counter 1"));
		capabilities.save(new CounterCapability(branch, consultation, counter));
		Queue queue = queues.save(new Queue(org, branch, consultation));
		User customer = users.save(new User(org, tag + "@example.com", "Customer"));
		customer.grantRole(RoleType.CUSTOMER);
		customer = users.save(customer);
		return new Fixture(org, branch, consultation, counter, queue, customer);
	}

	private record Fixture(Organization org, Branch branch, Service service,
			ServiceCounter counter, Queue queue, User customer) {
	}

	private StompSession connect(String token) throws Exception {
		WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
		return client
				.connectAsync("ws://localhost:" + port + "/ws?token=" + token,
						new StompSessionHandlerAdapter() {
						})
				.get(10, TimeUnit.SECONDS);
	}

	private String tokenFor(Fixture f) {
		credentials.setPassword(f.customer().getId(), FIXTURE_PASSWORD);
		return authService.login(f.customer().getEmail(), FIXTURE_PASSWORD).token();
	}

	/**
	 * A denied subscription terminates the server session, so cleanup must
	 * tolerate an already-closed connection.
	 */
	private static void safeDisconnect(StompSession session) {
		try {
			if (session.isConnected()) {
				session.disconnect();
			}
		} catch (IllegalStateException e) {
			// Already closed by the server after a denial — expected.
		}
	}

	private void subscribe(StompSession session, String destination,
			CompletableFuture<String> future) {
		session.subscribe(destination, new StompFrameHandler() {
			@Override
			public Type getPayloadType(StompHeaders headers) {
				return byte[].class;
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				String text = new String((byte[]) payload, java.nio.charset.StandardCharsets.UTF_8);
				received.add(text);
				future.complete(text);
			}
		});
	}

	@Test
	void snapshotRequestDeliversCurrentState() throws Exception {
		Fixture f = fixture(UUID.randomUUID().toString().substring(0, 8));
		StompSession session = connect(tokenFor(f));
		CompletableFuture<String> first = new CompletableFuture<>();
		subscribe(session, QueueRealtimeDestinations.topic(
				f.org().getId(), f.branch().getId(), f.queue().getId()), first);
		try {
			session.send(QueueRealtimeDestinations.snapshotRequest(
					f.org().getId(), f.branch().getId(), f.queue().getId()), null);

			QueueRealtimeState state = redisJson.fromJson(
					first.get(10, TimeUnit.SECONDS), QueueRealtimeState.class);

			assertThat(state.queueId()).isEqualTo(f.queue().getId());
			assertThat(state.waitingCount()).isZero();
			assertThat(state.waiting()).isEmpty();
		} finally {
			session.disconnect();
		}
	}

	@Test
	void joinBroadcastsUpdatedSnapshot() throws Exception {
		Fixture f = fixture(UUID.randomUUID().toString().substring(0, 8));
		StompSession session = connect(tokenFor(f));
		CompletableFuture<String> first = new CompletableFuture<>();
		CompletableFuture<String> second = new CompletableFuture<>();
		var seen = new java.util.concurrent.atomic.AtomicInteger();
		session.subscribe(QueueRealtimeDestinations.topic(
				f.org().getId(), f.branch().getId(), f.queue().getId()),
				new StompFrameHandler() {
					@Override
					public Type getPayloadType(StompHeaders headers) {
						return byte[].class;
					}

					@Override
					public void handleFrame(StompHeaders headers, Object payload) {
						String text = new String(
								(byte[]) payload, java.nio.charset.StandardCharsets.UTF_8);
						received.add(text);
						if (seen.incrementAndGet() == 1) {
							first.complete(text);
						} else {
							second.complete(text);
						}
					}
				});
		try {
			session.send(QueueRealtimeDestinations.snapshotRequest(
					f.org().getId(), f.branch().getId(), f.queue().getId()), null);
			first.get(10, TimeUnit.SECONDS);

			joinService.join(f.queue().getId(), f.customer().getId(), QueuePriority.NORMAL);

			QueueRealtimeState updated = redisJson.fromJson(
					second.get(10, TimeUnit.SECONDS), QueueRealtimeState.class);
			assertThat(updated.waitingCount()).isEqualTo(1);
			assertThat(updated.waiting().get(0).tokenNumber()).isEqualTo(1);
			assertThat(updated.generatedAt()).isNotNull();
		} finally {
			session.disconnect();
		}
	}

	@Test
	void tenantTopicsStayIsolatedForOperationalSessions() throws Exception {
		Fixture a = fixture(UUID.randomUUID().toString().substring(0, 8));
		Fixture b = fixture(UUID.randomUUID().toString().substring(0, 8));
		// Operational session bound to B's organization via an ACTIVE
		// membership: B's own topic is served first, then the cross-tenant
		// SUBSCRIBE is denied — which terminates the session, so the denied
		// subscription goes last (see ADR-021). Customers follow the
		// display rule instead (ADR-040): they may observe any ACTIVE
		// queue, so this test no longer uses a customer session for the
		// cross-tenant leg.
		User staffB = users.save(new User(b.org(),
				"staff-" + UUID.randomUUID() + "@example.com", "Staff B"));
		staffB.grantRole(RoleType.STAFF);
		staffB = users.save(staffB);
		memberships.save(new OrganizationMembership(staffB, b.org(), MembershipRole.STAFF));
		assignments.save(new com.sourabh.smartqueue.staff.StaffAssignment(
				b.org(), staffB, b.branch()));
		credentials.setPassword(staffB.getId(), FIXTURE_PASSWORD);
		String staffToken = authService.login(staffB.getEmail(), FIXTURE_PASSWORD).token();
		StompSession session = connect(staffToken);
		CompletableFuture<String> onA = new CompletableFuture<>();
		CompletableFuture<String> onB = new CompletableFuture<>();
		subscribe(session, QueueRealtimeDestinations.topic(
				b.org().getId(), b.branch().getId(), b.queue().getId()), onB);
		try {
			joinService.join(b.queue().getId(), b.customer().getId(), QueuePriority.NORMAL);

			QueueRealtimeState bState = redisJson.fromJson(
					onB.get(10, TimeUnit.SECONDS), QueueRealtimeState.class);
			assertThat(bState.queueId()).isEqualTo(b.queue().getId());

			subscribe(session, QueueRealtimeDestinations.topic(
					a.org().getId(), a.branch().getId(), a.queue().getId()), onA);
			joinService.join(a.queue().getId(), a.customer().getId(), QueuePriority.NORMAL);

			// Bounded absence proof: the cross-tenant SUBSCRIBE was denied,
			// so nothing for A may arrive. A bare timeout is the only honest
			// way to assert non-delivery.
			assertThatThrownBy(() -> onA.get(2, TimeUnit.SECONDS))
					.isInstanceOf(TimeoutException.class);
		} finally {
			safeDisconnect(session);
		}
	}

	@Test
	void payloadCarriesNoCustomerIdentity() throws Exception {
		Fixture f = fixture(UUID.randomUUID().toString().substring(0, 8));
		StompSession session = connect(tokenFor(f));
		CompletableFuture<String> first = new CompletableFuture<>();
		subscribe(session, QueueRealtimeDestinations.topic(
				f.org().getId(), f.branch().getId(), f.queue().getId()), first);
		try {
			joinService.join(f.queue().getId(), f.customer().getId(), QueuePriority.NORMAL);
			session.send(QueueRealtimeDestinations.snapshotRequest(
					f.org().getId(), f.branch().getId(), f.queue().getId()), null);

			String raw = first.get(10, TimeUnit.SECONDS);
			assertThat(raw)
					.doesNotContain(f.customer().getId().toString())
					.doesNotContain(f.customer().getEmail())
					.contains("\"tokenNumber\":1");
		} finally {
			session.disconnect();
		}
	}
}


