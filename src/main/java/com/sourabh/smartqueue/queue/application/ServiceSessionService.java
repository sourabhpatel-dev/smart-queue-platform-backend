package com.sourabh.smartqueue.queue.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.TenantAccessPolicy;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.common.api.ApiPage;
import com.sourabh.smartqueue.common.api.ApiPageRequest;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.queue.QueueEntry;
import com.sourabh.smartqueue.queue.QueueEntryNotFoundException;
import com.sourabh.smartqueue.queue.QueueEntryRepository;
import com.sourabh.smartqueue.queue.ServiceSession;
import com.sourabh.smartqueue.queue.ServiceSessionNotFoundException;
import com.sourabh.smartqueue.queue.ServiceSessionRepository;
import com.sourabh.smartqueue.queue.api.ServiceSessionResponse;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Service-session read adapter behind the tenant boundary (see ADR-027,
 * session reads).
 *
 * <p>Read-only by design: the 3L commands remain the sole lifecycle
 * authority, so this service never transitions anything. Visibility is
 * operational — managers/admins org-wide, assigned staff at their
 * branches; customers have no session views at all (their entry reads
 * already carry status and timestamps), enforced as {@code 403} on
 * collections and scope-safe {@code 404}s on items. "Current" reuses the
 * domain's own running definition ({@code endedAt IS NULL}, newest
 * wins); an idle counter's current-session sub-resource simply does not
 * exist ({@code 404} — which doubles as the derived occupancy signal).
 * PostgreSQL answers every read; Redis is never consulted.
 */
@Service
public class ServiceSessionService {

	/** API sort names to persistence properties — the allow-list, nothing else. */
	static final Map<String, String> SORTABLE = Map.of(
			"startedAt", "startedAt",
			"endedAt", "endedAt",
			"createdAt", "createdAt",
			"updatedAt", "updatedAt");

	private final ServiceSessionRepository sessions;
	private final BranchRepository branches;
	private final ServiceCounterRepository counters;
	private final QueueEntryRepository entries;
	private final UserRepository users;
	private final com.sourabh.smartqueue.service.ServiceRepository services;
	private final TenantAccessPolicy access;

	public ServiceSessionService(ServiceSessionRepository sessions,
			BranchRepository branches,
			ServiceCounterRepository counters,
			QueueEntryRepository entries,
			UserRepository users,
			com.sourabh.smartqueue.service.ServiceRepository services,
			TenantAccessPolicy access) {
		this.sessions = sessions;
		this.branches = branches;
		this.counters = counters;
		this.entries = entries;
		this.users = users;
		this.services = services;
		this.access = access;
	}

	@Transactional(readOnly = true)
	public ServiceSessionResponse getSession(AuthenticatedPrincipal principal,
			UUID sessionId) {
		return ServiceSessionResponse.from(scopedSessionForRead(principal, sessionId));
	}

	@Transactional(readOnly = true)
	public ApiPage<ServiceSessionResponse> listSessions(AuthenticatedPrincipal principal,
			int page, int size, List<String> sorts, UUID branchId, UUID counterId,
			UUID serviceId, UUID customerId, UUID queueEntryId, Boolean active,
			Instant from, Instant to) {
		if (from != null && to != null && from.isAfter(to)) {
			throw new InvalidRequestException("INVALID_TIME_RANGE",
					"Query parameter 'from' must not be after 'to'");
		}
		boolean manager = principal.hasAnyRole(RoleType.MANAGER, RoleType.ADMIN);
		boolean staff = principal.hasAnyRole(RoleType.STAFF);
		if (!manager && !staff) {
			throw new AccessDeniedException("session listing is operational-only");
		}
		UUID effectiveBranch = null;
		if (branchId != null) {
			effectiveBranch = scopedBranch(principal, branchId).getId();
		}
		if (staff && !manager) {
			// Staff name an assigned branch; the roster rule decides.
			if (effectiveBranch == null) {
				throw new InvalidRequestException("INVALID_REQUEST",
						"Query parameter 'branchId' is required for staff listing");
			}
			access.requireBranchOperationalAccess(principal, effectiveBranch);
		}
		Pageable pageable = ApiPageRequest.of(page, size, sorts, SORTABLE, "startedAt",
				Sort.Direction.DESC);
		Page<ServiceSession> result = sessions.search(principal.organizationId(),
				effectiveBranch, scopedCounterId(principal, counterId),
				scopedServiceId(principal, serviceId), scopedCustomerId(principal, customerId),
				scopedEntryId(principal, queueEntryId), active,
				from == null, from == null ? Instant.EPOCH : from,
				to == null, to == null ? Instant.EPOCH : to,
				pageable);
		return ApiPage.from(result.map(ServiceSessionResponse::from));
	}

	@Transactional(readOnly = true)
	public ServiceSessionResponse getCurrentSession(AuthenticatedPrincipal principal,
			UUID counterId) {
		ServiceCounter counter = scopedCounter(principal, counterId);
		access.requireBranchOperationalAccess(principal, counter.getBranch().getId());
		return sessions
				.findFirstByCounterIdAndEndedAtIsNullOrderByStartedAtDesc(counter.getId())
				.map(ServiceSessionResponse::from)
				.orElseThrow(() -> new ServiceSessionNotFoundException(
						"Counter has no active session"));
	}

	@Transactional(readOnly = true)
	public ApiPage<ServiceSessionResponse> listCounterSessions(
			AuthenticatedPrincipal principal, UUID counterId, int page, int size,
			List<String> sorts) {
		ServiceCounter counter = scopedCounter(principal, counterId);
		access.requireBranchOperationalAccess(principal, counter.getBranch().getId());
		Pageable pageable = ApiPageRequest.of(page, size, sorts, SORTABLE, "startedAt",
				Sort.Direction.DESC);
		Page<ServiceSession> result = sessions.search(principal.organizationId(), null,
				counter.getId(), null, null, null, null,
				true, Instant.EPOCH, true, Instant.EPOCH, pageable);
		return ApiPage.from(result.map(ServiceSessionResponse::from));
	}

	/**
	 * Privacy-first read: operationally authorized staff/managers see the
	 * row; everyone else — including same-org customers — gets the
	 * scope-safe {@code 404}, never a peek at another customer's session.
	 */
	private ServiceSession scopedSessionForRead(AuthenticatedPrincipal principal,
			UUID sessionId) {
		ServiceSession session = sessions.findById(sessionId)
				.orElseThrow(() -> new ServiceSessionNotFoundException(
						"Service session not found"));
		if (!session.getBranch().getOrganization().getId()
				.equals(principal.organizationId())) {
			throw new ServiceSessionNotFoundException("Service session not found");
		}
		try {
			access.requireBranchOperationalAccess(principal, session.getBranch().getId());
		} catch (AccessDeniedException e) {
			throw new ServiceSessionNotFoundException("Service session not found");
		}
		return session;
	}

	private Branch scopedBranch(AuthenticatedPrincipal principal, UUID branchId) {
		Branch branch = branches.findById(branchId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"BRANCH_NOT_FOUND", "Branch not found"));
		// Sessions are operational-only: the callers deny customers via
		// the policy (403); the equality only binds operational principals.
		if (principal.isOperational()
				&& !branch.getOrganization().getId().equals(principal.organizationId())) {
			throw new ResourceNotFoundException("BRANCH_NOT_FOUND", "Branch not found");
		}
		return branch;
	}

	private ServiceCounter scopedCounter(AuthenticatedPrincipal principal, UUID counterId) {
		ServiceCounter counter = counters.findById(counterId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"COUNTER_NOT_FOUND", "Counter not found"));
		if (principal.isOperational()
				&& !counter.getBranch().getOrganization().getId()
						.equals(principal.organizationId())) {
			throw new ResourceNotFoundException("COUNTER_NOT_FOUND", "Counter not found");
		}
		return counter;
	}

	private UUID scopedCounterId(AuthenticatedPrincipal principal, UUID counterId) {
		return counterId == null ? null : scopedCounter(principal, counterId).getId();
	}

	private UUID scopedServiceId(AuthenticatedPrincipal principal, UUID serviceId) {
		if (serviceId == null) {
			return null;
		}
		com.sourabh.smartqueue.service.Service service = services.findById(serviceId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"SERVICE_NOT_FOUND", "Service not found"));
		if (!service.getBranch().getOrganization().getId()
				.equals(principal.organizationId())) {
			throw new ResourceNotFoundException("SERVICE_NOT_FOUND", "Service not found");
		}
		return service.getId();
	}

	private UUID scopedCustomerId(AuthenticatedPrincipal principal, UUID customerId) {
		if (customerId == null) {
			return null;
		}
		User user = users.findById(customerId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"USER_NOT_FOUND", "User not found"));
		if (user.getOrganization() == null
				|| !user.getOrganization().getId().equals(principal.organizationId())) {
			throw new ResourceNotFoundException("USER_NOT_FOUND", "User not found");
		}
		return user.getId();
	}

	private UUID scopedEntryId(AuthenticatedPrincipal principal, UUID entryId) {
		if (entryId == null) {
			return null;
		}
		QueueEntry entry = entries.findByIdAndOrganizationId(entryId,
				principal.organizationId())
				.orElseThrow(() -> new QueueEntryNotFoundException(
						"unknown queue entry: " + entryId));
		return entry.getId();
	}
}
