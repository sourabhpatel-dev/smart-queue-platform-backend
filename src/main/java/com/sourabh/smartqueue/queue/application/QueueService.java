package com.sourabh.smartqueue.queue.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.TenantAccessPolicy;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.common.CustomerScope;
import com.sourabh.smartqueue.common.api.ApiPage;
import com.sourabh.smartqueue.common.api.ApiPageRequest;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.queue.InvalidQueueRequestException;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueAgingProperties;
import com.sourabh.smartqueue.queue.QueueBranchMismatchException;
import com.sourabh.smartqueue.queue.QueueCallService;
import com.sourabh.smartqueue.queue.QueueEntry;
import com.sourabh.smartqueue.queue.QueueEntryLifecycleService;
import com.sourabh.smartqueue.queue.QueueEntryNotFoundException;
import com.sourabh.smartqueue.queue.QueueEntryRepository;
import com.sourabh.smartqueue.queue.QueueEntryStatus;
import com.sourabh.smartqueue.queue.QueueJoinEstimate;
import com.sourabh.smartqueue.queue.QueueJoinService;
import com.sourabh.smartqueue.queue.QueueNotFoundException;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.QueueStatus;
import com.sourabh.smartqueue.queue.QueueWaitTimeEstimator;
import com.sourabh.smartqueue.queue.QueueWaitTimeQueries;
import com.sourabh.smartqueue.queue.WaitTimeEstimate;
import com.sourabh.smartqueue.queue.api.JoinQueueRequest;
import com.sourabh.smartqueue.queue.api.QueueDetailResponse;
import com.sourabh.smartqueue.queue.api.QueueEntryResponse;
import com.sourabh.smartqueue.queue.api.QueueResponse;
import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Queue REST adapter behind the tenant boundary (see ADR-027).
 *
 * <p>Thin by rule: scope checks, authorization, and view composition
 * live here; ranking, aging, claims, sessions, waits, and concurrency
 * stay in the engine services this class delegates to — never
 * re-implemented, never bypassed. Positions and waits come from the
 * shared rank query and estimator, so what a customer sees is what
 * {@code callNext} serves. PostgreSQL is read for every response;
 * Redis is never consulted (realtime degradation cannot touch REST
 * correctness).
 *
 * <p>Context discipline: the HTTP filters install organization scope,
 * which the lifecycle services, estimator, and rank reads consume. The
 * atomic claim additionally needs branch scope, so {@code callNext}
 * installs an explicit {@code (org, counter-branch)} context with
 * save/restore semantics around the delegation — never leaking it.
 */
@Service
public class QueueService {

	static final String QUEUE_CODE = "QUEUE_NOT_FOUND";

	/** API sort names to persistence properties — the allow-list, nothing else. */
	static final Map<String, String> ENTRY_SORTABLE = Map.of(
			"joinedAt", "joinedAt",
			"tokenNumber", "tokenNumber",
			"status", "status",
			"priority", "priority",
			"createdAt", "createdAt",
			"updatedAt", "updatedAt");

	private final QueueRepository queues;
	private final QueueEntryRepository entries;
	private final BranchRepository branches;
	private final ServiceCounterRepository counters;
	private final com.sourabh.smartqueue.service.ServiceRepository services;
	private final UserRepository users;
	private final QueueJoinService joinService;
	private final QueueCallService callService;
	private final QueueEntryLifecycleService lifecycle;
	private final QueueWaitTimeQueries rankQueries;
	private final QueueWaitTimeEstimator estimator;
	private final QueueAgingProperties aging;
	private final TenantAccessPolicy access;
	private final Clock clock;

	public QueueService(QueueRepository queues,
			QueueEntryRepository entries,
			BranchRepository branches,
			ServiceCounterRepository counters,
			com.sourabh.smartqueue.service.ServiceRepository services,
			UserRepository users,
			QueueJoinService joinService,
			QueueCallService callService,
			QueueEntryLifecycleService lifecycle,
			QueueWaitTimeQueries rankQueries,
			QueueWaitTimeEstimator estimator,
			QueueAgingProperties aging,
			TenantAccessPolicy access,
			Clock clock) {
		this.queues = queues;
		this.entries = entries;
		this.branches = branches;
		this.counters = counters;
		this.services = services;
		this.users = users;
		this.joinService = joinService;
		this.callService = callService;
		this.lifecycle = lifecycle;
		this.rankQueries = rankQueries;
		this.estimator = estimator;
		this.aging = aging;
		this.access = access;
		this.clock = clock;
	}

	@Transactional
	public QueueEntryResponse join(AuthenticatedPrincipal principal, UUID branchId,
			UUID queueId, JoinQueueRequest request) {
		Branch branch = scopedBranch(principal, branchId);
		Queue queue = scopedQueue(principal, queueId);
		if (!queue.getBranch().getId().equals(branch.getId())) {
			throw new QueueBranchMismatchException(
					"Queue belongs to another branch and cannot be joined here");
		}
		QueuePriority priority = parsePriority(request);
		if (priority != QueuePriority.NORMAL) {
			// Triage is operational: customers and unassigned staff join NORMAL.
			access.requireBranchOperationalAccess(principal, branch.getId());
		}
		// Every role joins for itself — no delegated joining exists.
		QueueEntry entry = joinService.join(queue.getId(), principal.userId(), priority);
		// The live view (position + wait) reads the fail-closed tenant
		// context: operational requests carry it from the filter, customers
		// get the queue's organization installed for the read (ADR-040).
		return inResourceScope(principal, entry, this::liveView);
	}
	@Transactional(readOnly = true)
	public List<QueueResponse> listQueues(AuthenticatedPrincipal principal, UUID branchId) {
		Branch branch = scopedBranch(principal, branchId);
		if (principal.isOperational()) {
			access.requireBranchAccess(principal, branchId);
		}
		// Customers: the branch was already validated as an ACTIVE branch
		// of an ACTIVE organization by scopedBranch (ADR-040); the display
		// payload carries tokens only, no personal data.
		return queues.findByBranchId(branch.getId()).stream()
				.map(QueueResponse::from)
				.sorted(Comparator.comparing(QueueResponse::serviceName)
						.thenComparing(QueueResponse::id))
				.toList();
	}

	@Transactional(readOnly = true)
	public QueueDetailResponse getQueueDetail(AuthenticatedPrincipal principal,
			UUID queueId) {
		Queue queue = scopedQueueOperational(principal, queueId);
		List<QueueEntry> ranked = rankQueries.waitingInRankOrder(queue.getId(),
				clock.instant().getEpochSecond(), aging.getIntervalMinutes(),
				aging.getMaxPromotions(), aging.isEnabled());
		List<QueueEntryResponse> waiting = ranked.stream()
				.map(entry -> viewOf(entry, rankPosition(ranked, entry), waitOrNull(entry)))
				.toList();
		List<QueueEntry> active = entries.findByQueueAndStatusIn(queue,
				List.of(QueueEntryStatus.CALLED, QueueEntryStatus.SERVING));
		List<QueueEntryResponse> called = active.stream()
				.filter(entry -> entry.getStatus() == QueueEntryStatus.CALLED)
				.sorted(Comparator.comparing(QueueEntry::getJoinedAt)
						.thenComparing(QueueEntry::getId))
				.map(entry -> viewOf(entry, 0, waitOrNull(entry)))
				.toList();
		List<QueueEntryResponse> serving = active.stream()
				.filter(entry -> entry.getStatus() == QueueEntryStatus.SERVING)
				.sorted(Comparator.comparing(QueueEntry::getJoinedAt)
						.thenComparing(QueueEntry::getId))
				.map(entry -> viewOf(entry, 0, waitOrNull(entry)))
				.toList();
		List<QueueEntryResponse> live = new ArrayList<>(waiting);
		live.addAll(called);
		live.addAll(serving);
		return new QueueDetailResponse(QueueResponse.from(queue), waiting.size(),
				called.size(), serving.size(), List.copyOf(live), clock.instant());
	}

	/**
	 * Estimate waiting time if a new entry were to join the queue now.
	 * Validates: branch + same org, service + same branch, queue exists,
	 * service active, queue active. Returns aggregate wait snapshot using the
	 * shared estimator scope (same formula as per-entry {@link WaitTimeEstimate}
	 * with new-entry position = last among current WAITING).
	 *
	 * @return 200 with estimate, or 400/404/422 on validation failure.
	 * @throws ResourceNotFoundException for an unknown/foreign branch or service
	 * @throws QueueBranchMismatchException if the service belongs to another branch
	 * @throws QueueNotFoundException if no queue exists for the branch/service pair
	 * @throws InvalidRequestException ({@code QUEUE_INACTIVE}/{@code SERVICE_INACTIVE})
	 *         if the queue or service is not active
	 */
	@Transactional(readOnly = true)
	public QueueJoinEstimate estimateJoin(AuthenticatedPrincipal principal, UUID branchId,
			UUID serviceId) {
		Branch branch = scopedBranch(principal, branchId);

		// Service must exist and belong to the same branch (and organization).
		com.sourabh.smartqueue.service.Service service = services.findById(serviceId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"SERVICE_NOT_FOUND", "Service not found in this branch"));
		if (!service.getBranch().getId().equals(branch.getId())) {
			throw new QueueBranchMismatchException(
					"Service does not belong to the requested branch");
		}

		// Queue must exist for this branch + service.
		Queue queue = queues.findByBranchIdAndServiceId(branch.getId(), serviceId)
				.orElseThrow(() -> new QueueNotFoundException(
						"Queue not found for branch and service"));

		// Queue must be active — do not report a misleading positive wait.
		if (queue.getStatus() != QueueStatus.ACTIVE) {
			throw new InvalidRequestException(
					"QUEUE_INACTIVE", "Queue is not active");
		}

		// Service must be active.
		if (service.getStatus() != com.sourabh.smartqueue.service.ServiceStatus.ACTIVE) {
			throw new InvalidRequestException(
					"SERVICE_INACTIVE", "Service is not active");
		}

		// The estimator reads the fail-closed tenant context. Operational
		// requests carry it from the filter; for every customer the
		// branch's organization is installed for the delegation only
		// (ADR-040 bridge — the branch was authorized above, and no JWT
		// vintage changes behavior).
		if (principal.isOperational()) {
			return estimator.estimateJoin(queue.getId());
		}
		TenantContext previous = TenantContextHolder.getOrNull();
		TenantContextHolder.set(new TenantContext(branch.getOrganization().getId(), null));
		try {
			return estimator.estimateJoin(queue.getId());
		} finally {
			TenantContextHolder.restore(previous);
		}
	}

	@Transactional(readOnly = true)
	public ApiPage<QueueEntryResponse> listEntries(AuthenticatedPrincipal principal,
			UUID queueId, int page, int size, List<String> sorts, String statusFilter,
			String priorityFilter, UUID customerId) {
		Queue queue = scopedQueueOperational(principal, queueId);
		if (customerId != null) {
			scopedUser(principal, customerId);
		}
		Pageable pageable = ApiPageRequest.of(page, size, sorts, ENTRY_SORTABLE,
				"joinedAt");
		Page<QueueEntry> result = entries.search(queue.getId(),
				principal.organizationId(), customerId, parseStatus(statusFilter),
				parsePriorityFilter(priorityFilter), pageable);
		// Record listing: no live positions/waits in bulk pages (documented
		// cost bound — the snapshot and single reads carry them instead).
		return ApiPage.from(result.map(entry -> viewOf(entry, null, null)));
	}

	@Transactional(readOnly = true)
	public QueueEntryResponse getEntry(AuthenticatedPrincipal principal, UUID entryId) {
		return inResourceScope(principal, scopedEntryForRead(principal, entryId),
				entry -> liveView(entry));
	}

	@Transactional
	public QueueEntryResponse callNext(AuthenticatedPrincipal principal, UUID counterId) {
		ServiceCounter counter = counters.findById(counterId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"COUNTER_NOT_FOUND", "Counter not found"));
		if (principal.isOperational()
				&& !counter.getBranch().getOrganization().getId()
						.equals(principal.organizationId())) {
			throw new ResourceNotFoundException("COUNTER_NOT_FOUND", "Counter not found");
		}
		access.requireBranchOperationalAccess(principal, counter.getBranch().getId());
		// The atomic claim needs branch scope: install it explicitly around
		// the delegation with save/restore, never leaking into the caller.
		TenantContext previous = TenantContextHolder.getOrNull();
		TenantContextHolder.set(new TenantContext(principal.organizationId(),
				counter.getBranch().getId()));
		try {
			return liveView(callService.callNext(counter.getId()));
		} finally {
			TenantContextHolder.restore(previous);
		}
	}

	@Transactional
	public QueueEntryResponse startServing(AuthenticatedPrincipal principal,
			UUID entryId, UUID counterId) {
		QueueEntry entry = scopedEntry(principal, entryId);
		access.requireBranchOperationalAccess(principal, entry.getBranch().getId());
		ServiceCounter counter = counters.findById(counterId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"COUNTER_NOT_FOUND", "Counter not found"));
		if (principal.isOperational()
				&& !counter.getBranch().getOrganization().getId()
						.equals(principal.organizationId())) {
			throw new ResourceNotFoundException("COUNTER_NOT_FOUND", "Counter not found");
		}
		return liveView(lifecycle.startServing(entry.getId(), counter.getId()));
	}

	@Transactional
	public QueueEntryResponse completeEntry(AuthenticatedPrincipal principal,
			UUID entryId) {
		QueueEntry entry = scopedEntry(principal, entryId);
		access.requireBranchOperationalAccess(principal, entry.getBranch().getId());
		return liveView(lifecycle.complete(entry.getId()));
	}

	@Transactional
	public QueueEntryResponse cancelEntry(AuthenticatedPrincipal principal, UUID entryId) {
		QueueEntry entry = scopedEntry(principal, entryId);
		if (!entry.getCustomer().getId().equals(principal.userId())) {
			if (!principal.isOperational()) {
				// Commands deny explicitly (403): a customer may only
				// cancel their own entries (ADR-040); customers never
				// touch others' rows.
				throw new AccessDeniedException(
						"customers may only cancel their own queue entries");
			}
			// Only the owner or branch management cancels; staff front-desk
			// cancellation for others is deferred.
			access.requireBranchManagement(principal, entry.getBranch().getId());
		}
		// The lifecycle service reads the fail-closed tenant context. For a
		// platform-level customer the scope is the entry's own
		// organization — derived from the loaded row above, never
		// from client input (ADR-040 bridge).
		return inResourceScope(principal, entry, e -> liveView(lifecycle.cancel(e.getId())));
	}

	@Transactional
	public QueueEntryResponse markNoShow(AuthenticatedPrincipal principal, UUID entryId) {
		QueueEntry entry = scopedEntry(principal, entryId);
		access.requireBranchOperationalAccess(principal, entry.getBranch().getId());
		return liveView(lifecycle.markNoShow(entry.getId()));
	}

	/**
	 * Branch resolution with the ADR-040 split: operational principals
	 * must reach branches of their own (membership-resolved) organization;
	 * platform-level customers reach any ACTIVE branch of an ACTIVE
	 * organization — the branch names the organization for the request,
	 * and no membership is implied by reaching it.
	 */
	private Branch scopedBranch(AuthenticatedPrincipal principal, UUID branchId) {
		Branch branch = branches.findById(branchId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"BRANCH_NOT_FOUND", "Branch not found"));
		if (principal.isOperational()) {
			if (!branch.getOrganization().getId().equals(principal.organizationId())) {
				throw new ResourceNotFoundException("BRANCH_NOT_FOUND", "Branch not found");
			}
			// ADR-041: operators of a non-ACTIVE organization (pending,
			// rejected, deactivated) reach nothing — membership alone is
			// not liveness.
			if (branch.getOrganization().getStatus()
					!= com.sourabh.smartqueue.organization.OrganizationStatus.ACTIVE) {
				throw new ResourceNotFoundException("BRANCH_NOT_FOUND", "Branch not found");
			}
			return branch;
		}
		CustomerScope.requireActiveBranch(branch, "BRANCH_NOT_FOUND", "Branch not found");
		return branch;
	}

	/**
	 * Queue resolution: operational principals load through their
	 * organization scope; platform-level customers load by id — the
	 * queue's own branch/ACTIVE state is validated by the caller and the
	 * join service, and the queue's organization is the row's historical
	 * context, never an authorization input (ADR-040).
	 */
	private Queue scopedQueue(AuthenticatedPrincipal principal, UUID queueId) {
		if (principal.isOperational()) {
			return queues.findByIdAndOrganizationId(queueId, principal.organizationId())
					.orElseThrow(() -> new QueueNotFoundException("Queue not found"));
		}
		return queues.findById(queueId)
				.orElseThrow(() -> new QueueNotFoundException("Queue not found"));
	}

	private Queue scopedQueueOperational(AuthenticatedPrincipal principal, UUID queueId) {
		Queue queue = scopedQueue(principal, queueId);
		access.requireBranchOperationalAccess(principal, queue.getBranch().getId());
		return queue;
	}

	private QueueEntry scopedEntry(AuthenticatedPrincipal principal, UUID entryId) {
		if (principal.isOperational()) {
			return entries.findByIdAndOrganizationId(entryId, principal.organizationId())
					.orElseThrow(() -> new QueueEntryNotFoundException(
							"unknown queue entry: " + entryId));
		}
		// Customer: the row loads regardless of its organization (the
		// organization on the row is its historical transaction context,
		// never an authorization input — ADR-040). Reads stay
		// privacy-first (owner or denial), and commands deny explicitly;
		// those rules live in the callers, not here.
		return entries.findById(entryId)
				.orElseThrow(() -> new QueueEntryNotFoundException(
						"unknown queue entry: " + entryId));
	}

	/**
	 * Privacy-first read: the owner and operationally authorized
	 * staff/managers see the row; everyone else — including same-org
	 * customers and unassigned staff — gets the scope-safe {@code 404},
	 * never a peek at another customer's entry.
	 */
	private QueueEntry scopedEntryForRead(AuthenticatedPrincipal principal, UUID entryId) {
		QueueEntry entry = scopedEntry(principal, entryId);
		if (entry.getCustomer().getId().equals(principal.userId())) {
			return entry;
		}
		try {
			access.requireBranchOperationalAccess(principal, entry.getBranch().getId());
		} catch (AccessDeniedException e) {
			throw new QueueEntryNotFoundException(
					"unknown queue entry: " + entryId);
		}
		return entry;
	}

	/**
	 * The ADR-040 compatibility bridge for customer reads and lifecycle
	 * commands. Operational requests already carry a request-scoped
	 * {@link TenantContext} installed by {@code TenantContextFilter}; the
	 * engine services below consume that fail-closed context. A
	 * platform-level customer has no global context, so the scope is
	 * installed here from the <em>already-authorized row</em> (owner
	 * checked or branch-denied before this call) for the duration of the
	 * delegation and restored afterwards. It can never widen access: the
	 * caller's authorization happened against this same row. Every
	 * customer takes this path — including legacy-token customers whose
	 * token still carries an organization, so behavior never depends on
	 * the JWT vintage (a legacy organization value is not authorization).
	 */
	private <T> T inResourceScope(AuthenticatedPrincipal principal, QueueEntry entry,
			java.util.function.Function<QueueEntry, T> action) {
		if (principal.isOperational()) {
			return action.apply(entry);
		}
		TenantContext previous = TenantContextHolder.getOrNull();
		TenantContextHolder.set(new TenantContext(entry.getOrganization().getId(), null));
		try {
			return action.apply(entry);
		} finally {
			TenantContextHolder.restore(previous);
		}
	}

	private void scopedUser(AuthenticatedPrincipal principal, UUID userId) {
		User user = users.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"USER_NOT_FOUND", "User not found"));
		if (user.getOrganization() == null
				|| !user.getOrganization().getId().equals(principal.organizationId())) {
			throw new ResourceNotFoundException("USER_NOT_FOUND", "User not found");
		}
	}

	private QueuePriority parsePriority(JoinQueueRequest request) {
		if (request == null || request.priority() == null
				|| request.priority().isBlank()) {
			return QueuePriority.NORMAL;
		}
		try {
			return QueuePriority.valueOf(request.priority().trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new InvalidQueueRequestException(
					"Unknown priority: " + request.priority()
							+ ". Supported: NORMAL, PRIORITY, EMERGENCY.");
		}
	}

	private QueueEntryStatus parseStatus(String statusFilter) {
		if (statusFilter == null) {
			return null;
		}
		try {
			return QueueEntryStatus.valueOf(statusFilter.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new InvalidRequestException("INVALID_STATUS",
					"Unsupported status filter: " + statusFilter
							+ ". Supported: WAITING, CALLED, SERVING, COMPLETED, CANCELLED, NO_SHOW.");
		}
	}

	private QueuePriority parsePriorityFilter(String priorityFilter) {
		if (priorityFilter == null) {
			return null;
		}
		try {
			return QueuePriority.valueOf(priorityFilter.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new InvalidRequestException("INVALID_PRIORITY",
					"Unsupported priority filter: " + priorityFilter
							+ ". Supported: NORMAL, PRIORITY, EMERGENCY.");
		}
	}

	/**
	 * Live position + wait for one entry: WAITING ranks 1-based in the
	 * shared claim order (absent — claimed concurrently — yields null);
	 * CALLED/SERVING mean effectively now (position 0, estimator 0);
	 * terminal rows carry neither.
	 */
	private QueueEntryResponse liveView(QueueEntry entry) {
		return switch (entry.getStatus()) {
			case WAITING -> {
				List<QueueEntry> ranked = rankQueries.waitingInRankOrder(
						entry.getQueue().getId(), clock.instant().getEpochSecond(),
						aging.getIntervalMinutes(), aging.getMaxPromotions(),
						aging.isEnabled());
				yield viewOf(entry, rankPosition(ranked, entry), waitOrNull(entry));
			}
			case CALLED, SERVING -> viewOf(entry, 0, waitOrNull(entry));
			case COMPLETED, CANCELLED, NO_SHOW -> viewOf(entry, null, null);
		};
	}

	private Integer rankPosition(List<QueueEntry> ranked, QueueEntry entry) {
		int index = -1;
		for (int i = 0; i < ranked.size(); i++) {
			if (ranked.get(i).getId().equals(entry.getId())) {
				index = i;
				break;
			}
		}
		return index < 0 ? null : index + 1;
	}

	private Integer waitOrNull(QueueEntry entry) {
		return estimator.estimate(entry.getId()).map(WaitTimeEstimate::waitMinutes)
				.orElse(null);
	}

	private QueueEntryResponse viewOf(QueueEntry entry, Integer position,
			Integer waitMinutes) {
		return QueueEntryResponse.of(entry, position, waitMinutes);
	}
}

