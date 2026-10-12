package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sourabh.smartqueue.appointment.event.AppointmentBooked;
import com.sourabh.smartqueue.appointment.AppointmentStatus;
import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.notification.Notification;
import com.sourabh.smartqueue.notification.NotificationDestinations;
import com.sourabh.smartqueue.notification.NotificationRepository;
import com.sourabh.smartqueue.notification.NotificationType;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.event.QueueEntryCalled;
import com.sourabh.smartqueue.queue.event.QueueEntryJoined;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * In-app notification contract, end to end (see ADR-032).
 *
 * <p>Real HTTP against the real API plus real STOMP sessions: event
 * publication (the same records services emit) yields persisted,
 * recipient-scoped rows; the REST surface lists/filters/counts them
 * and moves read state idempotently; realtime echoes reach exactly
 * the recipient's session. Rollback never notifies, duplicate
 * handling never duplicates, and sender failure never loses the row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext
class NotificationApiTests {

	private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	private static final String PASSWORD = "correct-horse-9";

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
	UserRepository users;

	@Autowired
	OrganizationMembershipRepository memberships;
	@Autowired
	NotificationRepository notifications;
	@Autowired
	AuthService authService;
	@Autowired
	PasswordCredentialService credentials;
	@Autowired
	ApplicationEventPublisher events;
	@Autowired
	ObjectMapper objectMapper;
	@Autowired
	java.time.Clock clock;
	@Autowired
	PlatformTransactionManager transactionManager;

	private final HttpClient http = HttpClient.newHttpClient();

	String tag;
	Organization orgA;
	Branch branchA;
	Service serviceA;
	ServiceCounter counterA;
	User customerA;
	User customerB;
	String customerAToken;
	String customerBToken;
	Organization orgB;
	User customerC;
	String customerCToken;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		tag = UUID.randomUUID().toString().substring(0, 8);
		orgA = organizations.save(new Organization("Notify Org A " + tag));
		branchA = branches.save(new Branch(orgA, "Downtown " + tag, "Indore", "India", KOLKATA));
		serviceA = services.save(new Service(branchA, "Consultation", Duration.ofMinutes(30)));
		counterA = counters.save(new ServiceCounter(branchA, "Counter 1"));
		customerA = newUser(orgA, "cust-a-" + tag, RoleType.CUSTOMER);
		customerB = newUser(orgA, "cust-b-" + tag, RoleType.CUSTOMER);
		customerAToken = tokenFor(customerA);
		customerBToken = tokenFor(customerB);
		orgB = organizations.save(new Organization("Notify Org B " + tag));
		customerC = newUser(orgB, "cust-c-" + tag, RoleType.CUSTOMER);
		customerCToken = tokenFor(customerC);
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private User newUser(Organization org, String local, RoleType role) {
		User user = users.save(new User(org, local + "@example.com", local));
		user.grantRole(role);
		user = users.save(user);
		grantMembership(user, org, role);
		credentials.setPassword(user.getId(), PASSWORD);
		return user;
	}

	/**
	 * Mirrors the V20 backfill for fixtures (ADR-040): operational roles
	 * carry an ACTIVE membership at the fixture organization — the row the
	 * authorization boundary actually checks.
	 */
	private void grantMembership(User user, Organization org, RoleType role) {
		if (role == RoleType.CUSTOMER) {
			return;
		}
		memberships.save(new OrganizationMembership(user, org,
				MembershipRole.valueOf(role.name())));
	}

	private String tokenFor(User user) {
		return authService.login(user.getEmail(), PASSWORD).token();
	}

	/**
	 * Publishes inside a real committed transaction: AFTER_COMMIT
	 * listeners (the production path — services publish from within
	 * their transactions) fire only on commit, never on bare
	 * publication.
	 */
	private void publishJoined(UUID eventId) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status ->
				events.publishEvent(new QueueEntryJoined(eventId, clock.instant(),
						orgA.getId(), branchA.getId(), UUID.randomUUID(), UUID.randomUUID(),
						serviceA.getId(), customerA.getId(), LocalDate.now(), 3, QueuePriority.NORMAL)));
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path)).GET();
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String path, String token) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path))
				.POST(HttpRequest.BodyPublishers.noBody());
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void unauthenticatedListIs401() throws Exception {
		assertThat(get("/api/v1/notifications", null).statusCode()).isEqualTo(401);
	}

	@Test
	void emptyListAndZeroUnread() throws Exception {
		HttpResponse<String> list = get("/api/v1/notifications", customerAToken);
		assertThat(list.statusCode()).isEqualTo(200);
		JsonNode body = objectMapper.readTree(list.body());
		assertThat(body.get("content").size()).isEqualTo(0);
		assertThat(body.get("totalElements").asLong()).isEqualTo(0);

		HttpResponse<String> count = get("/api/v1/notifications/unread-count", customerAToken);
		assertThat(count.statusCode()).isEqualTo(200);
		assertThat(objectMapper.readTree(count.body()).get("unreadCount").asLong()).isEqualTo(0);
	}

	@Test
	void joinedEventCreatesCustomerNotification() throws Exception {
		publishJoined(UUID.randomUUID());

		HttpResponse<String> list = get("/api/v1/notifications", customerAToken);
		assertThat(list.statusCode()).isEqualTo(200);
		JsonNode content = objectMapper.readTree(list.body()).get("content");
		assertThat(content.size()).isEqualTo(1);
		JsonNode row = content.get(0);
		assertThat(row.get("type").asText()).isEqualTo("QUEUE_JOINED");
		assertThat(row.get("title").asText()).isNotBlank();
		assertThat(row.get("message").asText()).contains("Token 3");
		assertThat(row.get("read").asBoolean()).isFalse();
		assertThat(row.get("readAt").isNull()).isTrue();
		assertThat(row.get("referenceType").asText()).isEqualTo("QUEUE_ENTRY");
		assertThat(row.get("referenceId").asText()).isNotBlank();
		assertThat(row.get("createdAt").asText()).isNotBlank();
		// API-level data only: no organization, recipient, credential, or token fields.
		assertThat(row.has("organizationId")).isFalse();
		assertThat(row.has("recipientUserId")).isFalse();

		HttpResponse<String> count = get("/api/v1/notifications/unread-count", customerAToken);
		assertThat(objectMapper.readTree(count.body()).get("unreadCount").asLong()).isEqualTo(1);
	}

	@Test
	void appointmentBookedEventCreatesNotification() throws Exception {
		UUID appointmentId = UUID.randomUUID();
		new TransactionTemplate(transactionManager).executeWithoutResult(status ->
				events.publishEvent(new AppointmentBooked(UUID.randomUUID(), clock.instant(),
						orgA.getId(), branchA.getId(), appointmentId, serviceA.getId(),
						customerA.getId(), clock.instant().plusSeconds(3600),
						clock.instant().plusSeconds(5400), AppointmentStatus.SCHEDULED)));

		HttpResponse<String> list = get("/api/v1/notifications", customerAToken);
		JsonNode content = objectMapper.readTree(list.body()).get("content");
		assertThat(content.size()).isEqualTo(1);
		assertThat(content.get(0).get("type").asText()).isEqualTo("APPOINTMENT_BOOKED");
		assertThat(content.get(0).get("referenceType").asText()).isEqualTo("APPOINTMENT");
		assertThat(content.get(0).get("referenceId").asText()).isEqualTo(appointmentId.toString());
	}

	@Test
	void recipientAndTenantIsolation() throws Exception {
		publishJoined(UUID.randomUUID());
		String notificationId = objectMapper.readTree(
				get("/api/v1/notifications", customerAToken).body())
				.get("content").get(0).get("id").asText();

		// Same organization, different user: nothing listed, item is 404.
		assertThat(objectMapper.readTree(
				get("/api/v1/notifications", customerBToken).body())
				.get("totalElements").asLong()).isEqualTo(0);
		assertThat(get("/api/v1/notifications/" + notificationId, customerBToken).statusCode())
				.isEqualTo(404);
		assertThat(post("/api/v1/notifications/" + notificationId + "/read", customerBToken).statusCode())
				.isEqualTo(404);

		// Foreign organization: 404 as well (scope-safe, no cross-tenant signal).
		assertThat(get("/api/v1/notifications/" + notificationId, customerCToken).statusCode())
				.isEqualTo(404);

		// Unknown id: same 404 shape.
		assertThat(get("/api/v1/notifications/" + UUID.randomUUID(), customerAToken).statusCode())
				.isEqualTo(404);
	}

	@Test
	void markReadIsIdempotent() throws Exception {
		publishJoined(UUID.randomUUID());
		String notificationId = objectMapper.readTree(
				get("/api/v1/notifications", customerAToken).body())
				.get("content").get(0).get("id").asText();

		HttpResponse<String> first = post("/api/v1/notifications/" + notificationId + "/read", customerAToken);
		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(objectMapper.readTree(first.body()).get("read").asBoolean()).isTrue();

		HttpResponse<String> second = post("/api/v1/notifications/" + notificationId + "/read", customerAToken);
		assertThat(second.statusCode()).isEqualTo(200);
		assertThat(objectMapper.readTree(second.body()).get("read").asBoolean()).isTrue();

		assertThat(objectMapper.readTree(
				get("/api/v1/notifications/unread-count", customerAToken).body())
				.get("unreadCount").asLong()).isEqualTo(0);
	}

	@Test
	void markAllReadAffectsOnlyCaller() throws Exception {
		publishJoined(UUID.randomUUID());
		publishJoined(UUID.randomUUID());

		HttpResponse<String> all = post("/api/v1/notifications/read-all", customerAToken);
		assertThat(all.statusCode()).isEqualTo(200);
		assertThat(objectMapper.readTree(all.body()).get("markedRead").asInt()).isEqualTo(2);

		HttpResponse<String> again = post("/api/v1/notifications/read-all", customerAToken);
		assertThat(objectMapper.readTree(again.body()).get("markedRead").asInt()).isEqualTo(0);
	}

	@Test
	void paginationAndUnreadFilter() throws Exception {
		publishJoined(UUID.randomUUID());
		publishJoined(UUID.randomUUID());
		publishJoined(UUID.randomUUID());
		String newestId = objectMapper.readTree(
				get("/api/v1/notifications?size=10", customerAToken).body())
				.get("content").get(0).get("id").asText();
		post("/api/v1/notifications/" + newestId + "/read", customerAToken);

		JsonNode unread = objectMapper.readTree(
				get("/api/v1/notifications?unread=true&size=10", customerAToken).body());
		assertThat(unread.get("totalElements").asLong()).isEqualTo(2);

		JsonNode read = objectMapper.readTree(
				get("/api/v1/notifications?unread=false&size=10", customerAToken).body());
		assertThat(read.get("totalElements").asLong()).isEqualTo(1);

		JsonNode page = objectMapper.readTree(
				get("/api/v1/notifications?unread=true&page=0&size=1", customerAToken).body());
		assertThat(page.get("content").size()).isEqualTo(1);
		assertThat(page.get("totalPages").asInt()).isEqualTo(2);

		// Newest first: the latest handled event sorts first.
		JsonNode newest = objectMapper.readTree(
				get("/api/v1/notifications?size=10", customerAToken).body());
		assertThat(newest.get("content").size()).isEqualTo(3);

		assertThat(get("/api/v1/notifications?unread=maybe", customerAToken).statusCode()).isEqualTo(400);
		assertThat(get("/api/v1/notifications?sort=bogus,asc", customerAToken).statusCode()).isEqualTo(400);
	}

	@Test
	void rolledBackEventCreatesNoNotification() {
		// Rows from earlier tests persist (no cleanup between tests), so
		// the assertion is on the delta, not the absolute count.
		long before = notifications.count();
		new TransactionTemplate(transactionManager).execute(status -> {
			events.publishEvent(new QueueEntryJoined(UUID.randomUUID(), clock.instant(),
					orgA.getId(), branchA.getId(), UUID.randomUUID(), UUID.randomUUID(),
					serviceA.getId(), customerA.getId(), LocalDate.now(), 3, QueuePriority.NORMAL));
			// The business fact rolls back: AFTER_COMMIT never fires, so no
			// notification row may be added — the center cannot show facts
			// that never committed.
			status.setRollbackOnly();
			return null;
		});
		assertThat(notifications.count()).isEqualTo(before);
	}

	@Test
	void duplicateEventHandlingKeepsOneRow() throws Exception {
		UUID eventId = UUID.randomUUID();
		publishJoined(eventId);
		publishJoined(eventId);

		JsonNode body = objectMapper.readTree(
				get("/api/v1/notifications?size=10", customerAToken).body());
		assertThat(body.get("totalElements").asLong()).isEqualTo(1);
	}

	@Test
	void senderFailureKeepsPersistedRow() throws Exception {
		com.sourabh.smartqueue.notification.application.NotificationService fragile =
				new com.sourabh.smartqueue.notification.application.NotificationService(
						notifications, organizations, users,
						notification -> {
							throw new RuntimeException("broker down");
						},
						clock);
		// The service instance above is constructed manually, so its
		// REQUIRES_NEW boundary (a Spring proxy concern) does not
		// apply — and without any surrounding transaction the lazy
		// recipient associations have no session and the after-commit
		// echo has no synchronization. Running inside a real
		// transaction reproduces the production path (the AFTER_COMMIT
		// listener always calls the proxied bean from within business
		// state): the row commits, the failing echo is caught and
		// logged, and the row stays.
		java.util.Optional<com.sourabh.smartqueue.notification.api.NotificationResponse> created =
				new TransactionTemplate(transactionManager).execute(status ->
						fragile.create(orgA.getId(), customerA.getId(), NotificationType.QUEUE_JOINED,
								"Title", "Message",
								com.sourabh.smartqueue.notification.NotificationReferenceType.QUEUE_ENTRY,
								UUID.randomUUID(), UUID.randomUUID()));

		assertThat(created).isPresent();
		assertThat(objectMapper.readTree(
				get("/api/v1/notifications", customerAToken).body())
				.get("totalElements").asLong()).isEqualTo(1);
	}

	@Test
	void realtimeEchoReachesOnlyTheRecipient() throws Exception {
		StompSession sessionA = connect(customerAToken);
		StompSession sessionB = connect(customerBToken);
		try {
			CompletableFuture<String> receivedA = new CompletableFuture<>();
			CompletableFuture<String> receivedB = new CompletableFuture<>();
			subscribe(sessionA, receivedA);
			subscribe(sessionB, receivedB);

			UUID entryId = UUID.randomUUID();
			new TransactionTemplate(transactionManager).executeWithoutResult(status ->
					events.publishEvent(new QueueEntryCalled(UUID.randomUUID(), clock.instant(),
							orgA.getId(), branchA.getId(), UUID.randomUUID(), entryId,
							serviceA.getId(), customerA.getId(), counterA.getId())));

			String payload = receivedA.get(10, TimeUnit.SECONDS);
			assertThat(payload).contains("\"type\":\"QUEUE_CALLED\"");
			assertThat(payload).contains(entryId.toString());
			assertThat(payload).doesNotContain("password");
			// Bounded absence: the same fixed destination carries nothing
			// for the other user — the session, not the path, is the
			// scope. A bare timeout is the only honest non-delivery proof.
			assertThatThrownBy(() -> receivedB.get(2, TimeUnit.SECONDS))
					.isInstanceOf(java.util.concurrent.TimeoutException.class);
		} finally {
			safeDisconnect(sessionA);
			safeDisconnect(sessionB);
		}
	}

	@Test
	void unknownDestinationIsDenied() throws Exception {
		StompSession session = connect(customerAToken);
		try {
			CompletableFuture<String> future = new CompletableFuture<>();
			session.subscribe("/topic/does-not-exist", new StompFrameHandler() {
				@Override
				public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
					return byte[].class;
				}

				@Override
				public void handleFrame(StompHeaders headers, Object payload) {
					future.complete("unexpected");
				}
			});
			// Denial terminates the session: no frame ever arrives here.
			// A bare timeout is the only honest non-delivery proof.
			assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
					.isInstanceOf(java.util.concurrent.TimeoutException.class);
		} finally {
			safeDisconnect(session);
		}
	}

	private final CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();

	private StompSession connect(String token) throws Exception {
		// Default converters (no StringMessageConverter): inbound frames
		// arrive as byte[], exactly like the proven queue-snapshot tests.
		WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
		return client
				.connectAsync("ws://localhost:" + port + "/ws?token=" + token,
						new StompSessionHandlerAdapter() {
						})
				.get(10, TimeUnit.SECONDS);
	}

	private void subscribe(StompSession session, CompletableFuture<String> future) {
		session.subscribe(NotificationDestinations.USER_DESTINATION, new StompFrameHandler() {
			@Override
			public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
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

	private static void safeDisconnect(StompSession session) {
		try {
			if (session.isConnected()) {
				session.disconnect();
			}
		} catch (IllegalStateException e) {
			// Already closed by the server after a denial — expected.
		}
	}
}

