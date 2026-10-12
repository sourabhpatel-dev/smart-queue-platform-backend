package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
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
 * WebSocket authentication and subscription authorization (see ADR-021):
 * anonymous and forged sessions are rejected at CONNECT, and an
 * authenticated session cannot subscribe outside its organization/branch
 * scope. Non-transactional so broadcasts really happen; unique data per
 * run inside this class's own containers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class QueueRealtimeWebSocketSecurityTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	static final String PASSWORD = "Socket-Security-5";

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
	QueueJoinService joinService;

	@Autowired
	PasswordCredentialService credentials;

	@Autowired
	AuthService authService;

	@Autowired
	RedisJson redisJson;

	@Autowired
	com.sourabh.smartqueue.staff.StaffAssignmentRepository assignments;

	@Autowired
	com.sourabh.smartqueue.membership.OrganizationMembershipRepository memberships;

	private final List<String> received = new CopyOnWriteArrayList<>();

	@BeforeEach
	void clearReceived() {
		received.clear();
	}

	private Fixture fixture(String tag, RoleType role) {
		Organization org = organizations.save(new Organization("SeClinic " + tag));
		Branch branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		Service consultation = services.save(
				new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		ServiceCounter counter = counters.save(new ServiceCounter(branch, "Counter 1"));
		capabilities.save(new CounterCapability(branch, consultation, counter));
		Queue queue = queues.save(new Queue(org, branch, consultation));
		User user = users.save(new User(org, tag + "@example.com", "Se Customer"));
		user.grantRole(role);
		user = users.save(user);
		return new Fixture(org, branch, consultation, counter, queue, user);
	}

	private record Fixture(Organization org, Branch branch, Service service,
			ServiceCounter counter, Queue queue, User user) {
	}

	private String tokenFor(Fixture f) {
		credentials.setPassword(f.user().getId(), PASSWORD);
		return authService.login(f.user().getEmail(), PASSWORD).token();
	}

	private StompSession connect(String token) throws Exception {
		WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
		String url = "ws://localhost:" + port + "/ws" + (token == null ? "" : "?token=" + token);
		return client
				.connectAsync(url, new StompSessionHandlerAdapter() {
				})
				.get(10, TimeUnit.SECONDS);
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
	void anonymousConnectionIsRejected() {
		assertThatThrownBy(() -> connect(null)).isInstanceOf(ExecutionException.class);
	}

	@Test
	void forgedTokenConnectionIsRejected() {
		assertThatThrownBy(() -> connect("forged.token.value")).isInstanceOf(ExecutionException.class);
	}

	@Test
	void authenticatedOwnScopeSubscriptionReceives() throws Exception {
		Fixture f = fixture(UUID.randomUUID().toString().substring(0, 8), RoleType.CUSTOMER);
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
		} finally {
			session.disconnect();
		}
	}

	@Test
	void customerMayObserveAnotherOrganizationsActiveQueue() throws Exception {
		// ADR-040: a platform-level customer belongs to no organization and
		// may observe any ACTIVE queue's token-only topic. The destination
		// names the queue; the queue proves the scope.
		Fixture a = fixture(UUID.randomUUID().toString().substring(0, 8), RoleType.CUSTOMER);
		Fixture b = fixture(UUID.randomUUID().toString().substring(0, 8), RoleType.CUSTOMER);
		StompSession session = connect(tokenFor(a));
		CompletableFuture<String> foreign = new CompletableFuture<>();
		subscribe(session, QueueRealtimeDestinations.topic(
				b.org().getId(), b.branch().getId(), b.queue().getId()), foreign);
		try {
			joinService.join(b.queue().getId(), b.user().getId(), QueuePriority.NORMAL);

			QueueRealtimeState state = redisJson.fromJson(
					foreign.get(10, TimeUnit.SECONDS), QueueRealtimeState.class);
			assertThat(state.queueId()).isEqualTo(b.queue().getId());
		} finally {
			safeDisconnect(session);
		}
	}

	@Test
	void crossOrganizationOperationalSubscriptionIsDenied() throws Exception {
		// The display rule above is customer-only: staff from another
		// organization are still denied their foreign scope.
		Fixture a = fixture(UUID.randomUUID().toString().substring(0, 8), RoleType.STAFF);
		Fixture b = fixture(UUID.randomUUID().toString().substring(0, 8), RoleType.STAFF);
		assignments.save(new com.sourabh.smartqueue.staff.StaffAssignment(
				a.org(), a.user(), a.branch()));
		memberships.save(new com.sourabh.smartqueue.membership.OrganizationMembership(
				a.user(), a.org(),
				com.sourabh.smartqueue.membership.MembershipRole.STAFF));
		StompSession denied = connect(tokenFor(a));
		CompletableFuture<String> foreign = new CompletableFuture<>();
		subscribe(denied, QueueRealtimeDestinations.topic(
				b.org().getId(), b.branch().getId(), b.queue().getId()), foreign);
		try {
			joinService.join(b.queue().getId(), b.user().getId(), QueuePriority.NORMAL);

			assertThatThrownBy(() -> foreign.get(2, TimeUnit.SECONDS))
					.isInstanceOf(TimeoutException.class);
		} finally {
			safeDisconnect(denied);
		}
	}

	@Test
	void crossOrganizationSnapshotRequestIsDenied() throws Exception {
		Fixture a = fixture(UUID.randomUUID().toString().substring(0, 8), RoleType.CUSTOMER);
		Fixture b = fixture(UUID.randomUUID().toString().substring(0, 8), RoleType.CUSTOMER);
		StompSession session = connect(tokenFor(a));
		CompletableFuture<String> foreign = new CompletableFuture<>();
		subscribe(session, QueueRealtimeDestinations.topic(
				a.org().getId(), a.branch().getId(), a.queue().getId()), foreign);
		try {
			session.send(QueueRealtimeDestinations.snapshotRequest(
					b.org().getId(), b.branch().getId(), b.queue().getId()), null);

			assertThatThrownBy(() -> foreign.get(2, TimeUnit.SECONDS))
					.isInstanceOf(TimeoutException.class);
		} finally {
			safeDisconnect(session);
		}
	}

	@Test
	void unassignedStaffSubscriptionIsDenied() throws Exception {
		Fixture f = fixture(UUID.randomUUID().toString().substring(0, 8), RoleType.STAFF);
		StompSession session = connect(tokenFor(f));
		CompletableFuture<String> future = new CompletableFuture<>();
		subscribe(session, QueueRealtimeDestinations.topic(
				f.org().getId(), f.branch().getId(), f.queue().getId()), future);
		try {
			session.send(QueueRealtimeDestinations.snapshotRequest(
					f.org().getId(), f.branch().getId(), f.queue().getId()), null);

			assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
					.isInstanceOf(TimeoutException.class);
		} finally {
			safeDisconnect(session);
		}
		if (!received.isEmpty()) {
			fail("unassigned staff must receive nothing, got: " + received);
		}
	}
}
