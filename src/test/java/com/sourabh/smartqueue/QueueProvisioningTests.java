package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.QueueStatus;
import com.sourabh.smartqueue.queue.application.QueueProvisioningService;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.service.api.CreateServiceRequest;
import com.sourabh.smartqueue.service.application.ServiceManagementService;
import com.sourabh.smartqueue.staff.StaffAssignment;
import com.sourabh.smartqueue.staff.StaffAssignmentRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Queue auto-provisioning proofs (see ADR-038).
 *
 * <p>Covers the Phase 4K invariant — every service has exactly one
 * queue for its branch — at three levels: the application seam
 * (service creation provisions the queue in the same transaction),
 * the database backstop (concurrent duplicate inserts arbitrate to
 * one row), and the backfill migration statement (missing rows
 * created, existing rows preserved, re-runnable). Ends with the
 * black-box proof that a REST-created service is immediately
 * listable and joinable as a queue with operational claiming.
 *
 * <p>Non-transactional; every fixture name carries a per-test tag so
 * no test depends on another's rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class QueueProvisioningTests {

	static final String PASSWORD = "Queue-Prov-19";
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
	QueueRepository queues;

	@Autowired
	UserRepository users;

	@Autowired
	OrganizationMembershipRepository memberships;

	@Autowired
	StaffAssignmentRepository assignments;

	@Autowired
	ServiceManagementService serviceManagement;

	@Autowired
	QueueProvisioningService provisioning;

	@Autowired
	AuthService authService;

	@Autowired
	PasswordCredentialService credentials;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager txManager;

	@Autowired
	ObjectMapper objectMapper;

	private final HttpClient http = HttpClient.newHttpClient();

	String tag;
	Organization org;
	Branch branch;
	User manager;
	AuthenticatedPrincipal managerPrincipal;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		tag = UUID.randomUUID().toString().substring(0, 8);
		org = organizations.save(new Organization("Prov Org " + tag));
		branch = branches.save(new Branch(org, "Downtown " + tag,
				"Indore", "India", KOLKATA));
		manager = newUser("mgr-" + tag, RoleType.MANAGER);
		managerPrincipal = new AuthenticatedPrincipal(manager.getId(), org.getId(),
				manager.getEmail(), Set.of(RoleType.MANAGER));
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	// -- Application seam ------------------------------------------------------

	@Test
	void createServiceProvisionsActiveQueueInSameTransaction() {
		var response = serviceManagement.createService(managerPrincipal, branch.getId(),
				new CreateServiceRequest("Consult " + tag, "General", 30));

		Queue queue = queues.findByBranchIdAndServiceId(branch.getId(), response.id())
				.orElseThrow(() -> new AssertionError("queue missing for new service"));
		assertThat(queue.getOrganization().getId()).isEqualTo(org.getId());
		assertThat(queue.getBranch().getId()).isEqualTo(branch.getId());
		assertThat(queue.getService().getId()).isEqualTo(response.id());
		assertThat(queue.getStatus()).isEqualTo(QueueStatus.ACTIVE);
	}

	@Test
	void multipleServicesGetIndependentQueues() {
		var first = serviceManagement.createService(managerPrincipal, branch.getId(),
				new CreateServiceRequest("Consult " + tag, null, 30));
		var second = serviceManagement.createService(managerPrincipal, branch.getId(),
				new CreateServiceRequest("Xray " + tag, null, 15));

		Queue queueA = queues.findByBranchIdAndServiceId(branch.getId(), first.id())
				.orElseThrow(() -> new AssertionError("queue A missing"));
		Queue queueB = queues.findByBranchIdAndServiceId(branch.getId(), second.id())
				.orElseThrow(() -> new AssertionError("queue B missing"));
		assertThat(queueA.getId()).isNotEqualTo(queueB.getId());
		assertThat(queueA.getService().getId()).isEqualTo(first.id());
		assertThat(queueB.getService().getId()).isEqualTo(second.id());
	}

	@Test
	void servicesInDifferentBranchesProduceDifferentQueues() {
		Branch other = branches.save(new Branch(org, "Uptown " + tag,
				"Bhopal", "India", KOLKATA));
		var response = serviceManagement.createService(managerPrincipal, branch.getId(),
				new CreateServiceRequest("Consult " + tag, null, 30));
		var otherResponse = serviceManagement.createService(managerPrincipal, other.getId(),
				new CreateServiceRequest("Consult " + tag, null, 30));

		Queue queue = queues.findByBranchIdAndServiceId(branch.getId(), response.id())
				.orElseThrow(() -> new AssertionError("queue missing"));
		Queue otherQueue = queues.findByBranchIdAndServiceId(other.getId(), otherResponse.id())
				.orElseThrow(() -> new AssertionError("other queue missing"));
		assertThat(queue.getBranch().getId()).isEqualTo(branch.getId());
		assertThat(otherQueue.getBranch().getId()).isEqualTo(other.getId());
		assertThat(queue.getId()).isNotEqualTo(otherQueue.getId());
	}

	@Test
	void existingQueueIsReturnedUntouchedNeverDuplicated() {
		// Legacy shape: a service row without its queue (pre-provisioning data).
		com.sourabh.smartqueue.service.Service legacy = services.save(
				new com.sourabh.smartqueue.service.Service(branch,
						"Legacy " + tag, Duration.ofMinutes(20)));
		Queue manual = queues.save(new Queue(org, branch, legacy));
		manual.setStatus(QueueStatus.INACTIVE);
		queues.save(manual);

		Queue ensured = provisioning.ensureQueueForService(org, branch, legacy);

		assertThat(ensured.getId()).isEqualTo(manual.getId());
		assertThat(ensured.getStatus()).isEqualTo(QueueStatus.INACTIVE);
		assertThat(queues.findByBranchId(branch.getId())).hasSize(1);
	}

	// -- Database backstop -----------------------------------------------------

	@Test
	void concurrentDuplicateInsertsArbitrateToOneRow() throws Exception {
		com.sourabh.smartqueue.service.Service service = services.save(
				new com.sourabh.smartqueue.service.Service(branch,
						"Race " + tag, Duration.ofMinutes(20)));

		TransactionTemplate tx = new TransactionTemplate(txManager);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Callable<Boolean> attempt = () -> {
				try {
					tx.executeWithoutResult(status -> queues.saveAndFlush(
							new Queue(org, branch, service)));
					return true;
				} catch (RuntimeException e) {
					// Expected loser path: the unique backstop arbitrates
					// (DataIntegrityViolationException once translated).
					return false;
				}
			};
			List<Future<Boolean>> results = pool.invokeAll(List.of(attempt, attempt));
			long wins = 0;
			for (Future<Boolean> result : results) {
				if (result.get()) {
					wins++;
				}
			}
			assertThat(wins).isEqualTo(1);
			assertThat(queues.findByBranchIdAndServiceId(branch.getId(), service.getId()))
					.isPresent();
		} finally {
			pool.shutdownNow();
		}
	}

	// -- Backfill migration statement -------------------------------------------

	@Test
	void backfillCreatesMissingQueuesPreservesExistingAndIsIdempotent() {
		// A legacy service without a queue, plus a service whose queue
		// already exists (INACTIVE — must survive untouched).
		com.sourabh.smartqueue.service.Service missing = services.save(
				new com.sourabh.smartqueue.service.Service(branch,
						"Missing " + tag, Duration.ofMinutes(20)));
		com.sourabh.smartqueue.service.Service present = services.save(
				new com.sourabh.smartqueue.service.Service(branch,
						"Present " + tag, Duration.ofMinutes(20)));
		Queue kept = queues.save(new Queue(org, branch, present));
		kept.setStatus(QueueStatus.INACTIVE);
		queues.save(kept);

		runBackfillStatement();
		Queue backfilled = queues.findByBranchIdAndServiceId(branch.getId(), missing.getId())
				.orElseThrow(() -> new AssertionError("backfill did not create the queue"));
		assertThat(backfilled.getStatus()).isEqualTo(QueueStatus.ACTIVE);
		assertThat(backfilled.getOrganization().getId()).isEqualTo(org.getId());

		Queue untouched = queues.findByBranchIdAndServiceId(branch.getId(), present.getId())
				.orElseThrow(() -> new AssertionError("existing queue lost"));
		assertThat(untouched.getId()).isEqualTo(kept.getId());
		assertThat(untouched.getStatus()).isEqualTo(QueueStatus.INACTIVE);

		// Re-running creates zero rows: still exactly two queues.
		runBackfillStatement();
		assertThat(queues.findByBranchId(branch.getId())).hasSize(2);
	}

	/**
	 * The V19 backfill statement, executed against the real schema. Flyway
	 * applies the same statement on every boot (its application is proven
	 * by the green suite); this test proves its data semantics.
	 */
	private void runBackfillStatement() {
		jdbc.execute("""
				INSERT INTO queue (id, organization_id, branch_id, service_id, status, created_at, updated_at)
				SELECT gen_random_uuid(), b.organization_id, s.branch_id, s.id, 'ACTIVE', now(), now()
				FROM service s
				JOIN branch b ON b.id = s.branch_id
				WHERE NOT EXISTS (
				    SELECT 1 FROM queue q
				    WHERE q.branch_id = s.branch_id AND q.service_id = s.id
				)
				ON CONFLICT (branch_id, service_id) DO NOTHING
				""");
	}

	// -- Black-box: REST-created service is listable and joinable -------------

	@Test
	void restCreatedServiceIsImmediatelyListableAndJoinable() throws Exception {
		String managerToken = tokenFor(manager);
		User customer = newUser("cust-" + tag, RoleType.CUSTOMER);
		String customerToken = tokenFor(customer);
		User staff = newUser("staff-" + tag, RoleType.STAFF);
		assignments.save(new StaffAssignment(org, staff, branch));
		String staffToken = tokenFor(staff);

		HttpResponse<String> created = post("/api/v1/branches/" + branch.getId() + "/services",
				"{\"name\":\"Walkin " + tag + "\",\"description\":\"General\",\"durationMinutes\":30}",
				managerToken);
		assertThat(created.statusCode()).isEqualTo(201);
		UUID serviceId = UUID.fromString(json(created.body()).get("id").asString());

		// The provisioned queue appears in the branch queue list.
		HttpResponse<String> listed = get("/api/v1/branches/" + branch.getId() + "/queues",
				customerToken);
		assertThat(listed.statusCode()).isEqualTo(200);
		List<String> serviceIds = new ArrayList<>();
		json(listed.body()).forEach(node -> serviceIds.add(node.get("serviceId").asString()));
		assertThat(serviceIds).contains(serviceId.toString());
		String queueId = null;
		for (JsonNode node : json(listed.body())) {
			if (node.get("serviceId").asString().equals(serviceId.toString())) {
				queueId = node.get("id").asString();
			}
		}
		assertThat(queueId).isNotNull();

		// A customer can join it (server token/position/wait).
		HttpResponse<String> joined = post("/api/v1/branches/" + branch.getId()
				+ "/queues/" + queueId + "/entries", null, customerToken);
		assertThat(joined.statusCode()).isEqualTo(201);
		assertThat(json(joined.body()).get("tokenNumber").asInt()).isEqualTo(1);

		// Operational claiming still works: counter + capability + call-next.
		HttpResponse<String> counter = post("/api/v1/branches/" + branch.getId() + "/counters",
				"{\"name\":\"Counter 1 " + tag + "\"}", managerToken);
		assertThat(counter.statusCode()).isEqualTo(201);
		String counterId = json(counter.body()).get("id").asString();
		HttpResponse<String> capability = post("/api/v1/counters/" + counterId + "/capabilities",
				"{\"serviceId\":\"" + serviceId + "\"}", managerToken);
		assertThat(capability.statusCode()).isEqualTo(201);
		HttpResponse<String> claimed = post("/api/v1/counters/" + counterId + "/call-next",
				null, staffToken);
		assertThat(claimed.statusCode()).isEqualTo(200);
		assertThat(json(claimed.body()).get("tokenNumber").asInt()).isEqualTo(1);
	}

	// -- Fixtures ----------------------------------------------------------------

	private User newUser(String local, RoleType role) {
		User user = users.save(new User(org, local + "@example.com", local));
		user.grantRole(role);
		user = users.save(user);
		// Mirrors the V20 backfill for fixtures (ADR-040).
		if (role != RoleType.CUSTOMER) {
			memberships.save(new OrganizationMembership(user, org,
					MembershipRole.valueOf(role.name())));
		}
		credentials.setPassword(user.getId(), PASSWORD);
		return user;
	}

	private String tokenFor(User user) {
		return authService.login(user.getEmail(), PASSWORD).token();
	}

	private Pageable pageOfOne() {
		return PageRequest.of(0, 10);
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path))
				.GET();
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String path, String body, String token) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode json(String body) {
		try {
			return objectMapper.readTree(body);
		} catch (tools.jackson.core.JacksonException e) {
			throw new IllegalStateException("unreadable JSON response: " + body, e);
		}
	}
}




