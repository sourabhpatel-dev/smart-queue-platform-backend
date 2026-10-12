package com.sourabh.smartqueue.appointment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.appointment.Appointment;
import com.sourabh.smartqueue.appointment.AppointmentBookingService;
import com.sourabh.smartqueue.appointment.AppointmentBranchMismatchException;
import com.sourabh.smartqueue.appointment.AppointmentInvalidTransitionException;
import com.sourabh.smartqueue.appointment.AppointmentRepository;
import com.sourabh.smartqueue.appointment.AppointmentStatus;
import com.sourabh.smartqueue.appointment.InvalidAppointmentRequestException;
import com.sourabh.smartqueue.appointment.api.AppointmentResponse;
import com.sourabh.smartqueue.appointment.api.CreateAppointmentRequest;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.TenantAccessPolicy;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.common.CustomerScope;
import com.sourabh.smartqueue.common.api.ApiPage;
import com.sourabh.smartqueue.common.api.ApiPageRequest;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Appointment REST use cases behind the tenant boundary (see ADR-026).
 *
 * <p>A thin authorization/scoping shell around the Phase 2E domain: the
 * booking algorithm (calendar fit, DST, duration snapshot, overlap
 * arbitration) is never re-implemented here — this service validates the
 * request shape the HTTP layer cannot (future start, branch/service
 * agreement), pins the customer to the principal (no delegated booking in
 * any role), and delegates to {@link AppointmentBookingService}.
 * Lifecycle commands call the guarded entity transitions directly and
 * translate refusals to {@code 409} (deliberately not idempotent — the
 * domain rule wins).
 *
 * <p>Visibility is privacy-first: owners and operationally authorized
 * staff/managers read; everyone else gets the scope-safe {@code 404},
 * never a peek at another customer's appointment. Listing is
 * role-routed: customers are pinned to themselves (a foreign
 * {@code customerId} is an explicit {@code 403}); staff must name an
 * assigned branch; managers/admins roam their organization.
 */
@Service
public class AppointmentService {

	static final String CODE = "APPOINTMENT_NOT_FOUND";

	/** API sort names to persistence properties — the allow-list, nothing else. */
	static final Map<String, String> SORTABLE = Map.of(
			"startAt", "startAt",
			"endAt", "endAt",
			"status", "status",
			"createdAt", "createdAt",
			"updatedAt", "updatedAt");

	private final AppointmentRepository appointments;
	private final AppointmentBookingService booking;
	private final BranchRepository branches;
	private final com.sourabh.smartqueue.service.ServiceRepository services;
	private final UserRepository users;
	private final TenantAccessPolicy access;
	private final Clock clock;

	public AppointmentService(AppointmentRepository appointments,
			AppointmentBookingService booking,
			BranchRepository branches,
			com.sourabh.smartqueue.service.ServiceRepository services,
			UserRepository users,
			TenantAccessPolicy access,
			Clock clock) {
		this.appointments = appointments;
		this.booking = booking;
		this.branches = branches;
		this.services = services;
		this.users = users;
		this.access = access;
		this.clock = clock;
	}

	@Transactional
	public AppointmentResponse book(AuthenticatedPrincipal principal, UUID branchId,
			CreateAppointmentRequest request) {
		Branch branch = scopedBranch(principal, branchId);
		com.sourabh.smartqueue.service.Service service = services.findById(
				request.serviceId()).orElseThrow(() -> new ResourceNotFoundException(
						"SERVICE_NOT_FOUND", "Service not found"));
		if (principal.isOperational()
				&& !service.getBranch().getOrganization().getId()
						.equals(principal.organizationId())) {
			throw new ResourceNotFoundException("SERVICE_NOT_FOUND", "Service not found");
		}
		if (!service.getBranch().getId().equals(branch.getId())) {
			throw new AppointmentBranchMismatchException(
					"Service belongs to another branch and cannot be booked here");
		}
		if (!request.startAt().isAfter(clock.instant())) {
			throw new InvalidAppointmentRequestException(
					"Appointment must start in the future");
		}
		// Customer is always the principal — every role books for itself only.
		return AppointmentResponse.from(
				booking.book(principal.userId(), service.getId(), request.startAt()));
	}

	@Transactional(readOnly = true)
	public AppointmentResponse getAppointment(AuthenticatedPrincipal principal,
			UUID appointmentId) {
		return AppointmentResponse.from(scopedAppointmentForRead(principal, appointmentId));
	}

	@Transactional(readOnly = true)
	public ApiPage<AppointmentResponse> listAppointments(AuthenticatedPrincipal principal,
			int page, int size, List<String> sorts, String statusFilter, UUID branchId,
			UUID serviceId, UUID customerId, Instant from, Instant to) {
		if (from != null && to != null && from.isAfter(to)) {
			throw new InvalidRequestException("INVALID_TIME_RANGE",
					"Query parameter 'from' must not be after 'to'");
		}
		UUID effectiveBranch = branchId;
		if (branchId != null) {
			effectiveBranch = scopedBranch(principal, branchId).getId();
		}
		UUID effectiveService = null;
		if (serviceId != null) {
			effectiveService = scopedService(principal, serviceId).getId();
		}
		UUID effectiveCustomer;
		boolean manager = principal.hasAnyRole(RoleType.MANAGER, RoleType.ADMIN);
		boolean staff = principal.hasAnyRole(RoleType.STAFF);
		boolean customer = !principal.isOperational();
		if (customer) {
			// Platform-level customer: rows are scoped to the customer
			// across every organization; naming another id is denied.
			if (customerId != null && !customerId.equals(principal.userId())) {
				throw new AccessDeniedException(
						"customers may only list their own appointments");
			}
			effectiveCustomer = principal.userId();
		} else if (manager) {
			effectiveCustomer = customerId;
		} else {
			// Staff name an assigned branch; the roster rule decides.
			if (effectiveBranch == null) {
				throw new InvalidRequestException("INVALID_REQUEST",
						"Query parameter 'branchId' is required for staff listing");
			}
			access.requireBranchOperationalAccess(principal, effectiveBranch);
			effectiveCustomer = customerId;
		}
		if (!customer && customerId != null) {
			scopedUser(principal, customerId);
		}
		Pageable pageable = ApiPageRequest.of(page, size, sorts, SORTABLE, "startAt");
		// Customers search without an organization filter (they own rows at
		// any number of organizations); operational callers stay
		// organization-scoped (ADR-040).
		Page<Appointment> result = customer
				? appointments.searchForCustomer(effectiveCustomer, effectiveBranch,
						effectiveService, parseStatus(statusFilter),
						from == null, from == null ? Instant.EPOCH : from,
						to == null, to == null ? Instant.EPOCH : to,
						pageable)
				: appointments.search(principal.organizationId(),
						effectiveCustomer, effectiveBranch, effectiveService,
						parseStatus(statusFilter),
						from == null, from == null ? Instant.EPOCH : from,
						to == null, to == null ? Instant.EPOCH : to,
						pageable);
		return ApiPage.from(result.map(AppointmentResponse::from));
	}

	@Transactional
	public AppointmentResponse cancelAppointment(AuthenticatedPrincipal principal,
			UUID appointmentId) {
		Appointment appointment = scopedAppointment(principal, appointmentId);
		if (!appointment.getCustomer().getId().equals(principal.userId())) {
			if (!principal.isOperational()) {
				// Commands deny explicitly (403): the action targets a
				// caller-known resource, so policy — not existence — is the
				// message. A customer may only cancel their own
				// appointments (ADR-040); staff front-desk cancellation is
				// deferred, customers never touch others' rows.
				throw new AccessDeniedException(
						"customers may only cancel their own appointments");
			}
			// Only the owner or branch management cancels.
			access.requireBranchManagement(principal, appointment.getBranch().getId());
		}
		try {
			appointment.cancel();
		} catch (IllegalStateException e) {
			throw new AppointmentInvalidTransitionException(e.getMessage());
		}
		return AppointmentResponse.from(appointments.save(appointment));
	}

	@Transactional
	public AppointmentResponse completeAppointment(AuthenticatedPrincipal principal,
			UUID appointmentId) {
		Appointment appointment = scopedAppointment(principal, appointmentId);
		access.requireBranchOperationalAccess(principal, appointment.getBranch().getId());
		try {
			appointment.complete();
		} catch (IllegalStateException e) {
			throw new AppointmentInvalidTransitionException(e.getMessage());
		}
		return AppointmentResponse.from(appointments.save(appointment));
	}

	@Transactional
	public AppointmentResponse markNoShow(AuthenticatedPrincipal principal,
			UUID appointmentId) {
		Appointment appointment = scopedAppointment(principal, appointmentId);
		access.requireBranchOperationalAccess(principal, appointment.getBranch().getId());
		try {
			appointment.markNoShow();
		} catch (IllegalStateException e) {
			throw new AppointmentInvalidTransitionException(e.getMessage());
		}
		return AppointmentResponse.from(appointments.save(appointment));
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

	private com.sourabh.smartqueue.service.Service scopedService(
			AuthenticatedPrincipal principal, UUID serviceId) {
		com.sourabh.smartqueue.service.Service service = services.findById(serviceId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"SERVICE_NOT_FOUND", "Service not found"));
		if (principal.isOperational()) {
			if (!service.getBranch().getOrganization().getId()
					.equals(principal.organizationId())) {
				throw new ResourceNotFoundException("SERVICE_NOT_FOUND", "Service not found");
			}
			// ADR-041: operators of a non-ACTIVE organization reach
			// nothing — membership alone is not liveness.
			if (service.getBranch().getOrganization().getStatus()
					!= com.sourabh.smartqueue.organization.OrganizationStatus.ACTIVE) {
				throw new ResourceNotFoundException("SERVICE_NOT_FOUND", "Service not found");
			}
			return service;
		}
		CustomerScope.requireActiveBranch(service.getBranch(), "SERVICE_NOT_FOUND",
				"Service not found");
		return service;
	}

	private void scopedUser(AuthenticatedPrincipal principal, UUID userId) {
		User user = users.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"USER_NOT_FOUND", "User not found"));
		if (!user.getOrganization().getId().equals(principal.organizationId())) {
			throw new ResourceNotFoundException("USER_NOT_FOUND", "User not found");
		}
	}

	private Appointment scopedAppointment(AuthenticatedPrincipal principal,
			UUID appointmentId) {
		Appointment appointment = appointments.findById(appointmentId)
				.orElseThrow(() -> new ResourceNotFoundException(CODE,
						"Appointment not found"));
		if (principal.isOperational()) {
			if (!appointment.getOrganization().getId().equals(principal.organizationId())) {
				throw new ResourceNotFoundException(CODE, "Appointment not found");
			}
			return appointment;
		}
		// Customer: the row loads regardless of its organization (the
		// organization on the row is its historical transaction context,
		// never an authorization input — ADR-040). Reads stay
		// privacy-first (owner or denial), and commands deny explicitly;
		// those rules live in the callers, not here.
		return appointment;
	}

	/**
	 * Privacy-first read: the owner and operationally authorized
	 * staff/managers see the row; everyone else — including same-org
	 * customers and unassigned staff — gets the scope-safe {@code 404},
	 * never a peek at another customer's appointment.
	 */
	private Appointment scopedAppointmentForRead(AuthenticatedPrincipal principal,
			UUID appointmentId) {
		Appointment appointment = scopedAppointment(principal, appointmentId);
		if (appointment.getCustomer().getId().equals(principal.userId())) {
			return appointment;
		}
		try {
			access.requireBranchOperationalAccess(principal,
					appointment.getBranch().getId());
		} catch (AccessDeniedException e) {
			throw new ResourceNotFoundException(CODE, "Appointment not found");
		}
		return appointment;
	}

	private AppointmentStatus parseStatus(String statusFilter) {
		if (statusFilter == null) {
			return null;
		}
		try {
			return AppointmentStatus.valueOf(statusFilter.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new InvalidRequestException("INVALID_STATUS",
					"Unsupported status filter: " + statusFilter
							+ ". Supported: SCHEDULED, CONFIRMED, COMPLETED, CANCELLED, NO_SHOW.");
		}
	}
}

