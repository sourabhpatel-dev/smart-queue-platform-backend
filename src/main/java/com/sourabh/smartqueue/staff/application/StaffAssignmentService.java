package com.sourabh.smartqueue.staff.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import com.sourabh.smartqueue.staff.StaffAssignment;
import com.sourabh.smartqueue.staff.StaffAssignmentBranchMismatchException;
import com.sourabh.smartqueue.staff.StaffAssignmentConflictException;
import com.sourabh.smartqueue.staff.StaffAssignmentNotFoundException;
import com.sourabh.smartqueue.staff.StaffAssignmentRepository;
import com.sourabh.smartqueue.staff.StaffAssignmentStatus;
import com.sourabh.smartqueue.staff.api.AssignmentResponse;
import com.sourabh.smartqueue.staff.api.BranchStaffResponse;
import com.sourabh.smartqueue.user.InvalidUserRequestException;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Staff-assignment management behind the tenant boundary (see ADR-025).
 *
 * <p>Reads follow operational visibility (managers/admins org-wide,
 * assigned staff at their branches; customers never enumerate rosters).
 * A user's own assignment list additionally allows the subject themself.
 * Writes need {@code MANAGER}/{@code ADMIN} at the branch. Invariants:
 * user and branch share the principal's organization (anything else is a
 * scope-safe {@code 404}); the user must hold the {@code STAFF} role
 * (assignments for other roles would be rows the policy never consults);
 * the composite unique ignores status, so reactivation — not a second
 * row — is the path back after deactivation. Lifecycle commands are
 * idempotent and delete nothing: history stays queryable.
 */
@Service
public class StaffAssignmentService {

	static final String CODE = "STAFF_ASSIGNMENT_NOT_FOUND";

	/** API sort names to persistence properties — the allow-list, nothing else. */
	static final Map<String, String> SORTABLE = Map.of(
			"email", "user.email",
			"status", "status",
			"createdAt", "createdAt",
			"updatedAt", "updatedAt");

	private final StaffAssignmentRepository assignments;
	private final UserRepository users;
	private final BranchRepository branches;
	private final TenantAccessPolicy access;

	public StaffAssignmentService(StaffAssignmentRepository assignments,
			UserRepository users,
			BranchRepository branches,
			TenantAccessPolicy access) {
		this.assignments = assignments;
		this.users = users;
		this.branches = branches;
		this.access = access;
	}

	@Transactional(readOnly = true)
	public ApiPage<AssignmentResponse> listUserAssignments(AuthenticatedPrincipal principal,
			UUID userId, int page, int size, List<String> sorts, String statusFilter) {
		User user = scopedUser(principal, userId);
		access.requireSelfOrManagement(principal, userId);
		Pageable pageable = ApiPageRequest.of(page, size, sorts, SORTABLE, "createdAt");
		Page<StaffAssignment> result = statusFilter == null
				? assignments.findByUserId(user.getId(), pageable)
				: assignments.findByUserIdAndStatus(user.getId(),
						parseStatus(statusFilter), pageable);
		return ApiPage.from(result.map(AssignmentResponse::from));
	}

	@Transactional(readOnly = true)
	public ApiPage<BranchStaffResponse> listBranchStaff(AuthenticatedPrincipal principal,
			UUID branchId, int page, int size, List<String> sorts, String statusFilter) {
		Branch branch = scopedBranch(principal, branchId);
		access.requireBranchOperationalAccess(principal, branchId);
		Pageable pageable = ApiPageRequest.of(page, size, sorts, SORTABLE, "createdAt");
		Page<StaffAssignment> result = statusFilter == null
				? assignments.findByBranchId(branch.getId(), pageable)
				: assignments.findByBranchIdAndStatus(branch.getId(),
						parseStatus(statusFilter), pageable);
		return ApiPage.from(result.map(BranchStaffResponse::from));
	}

	@Transactional(readOnly = true)
	public AssignmentResponse getAssignment(AuthenticatedPrincipal principal,
			UUID assignmentId) {
		return AssignmentResponse.from(scopedAssignmentForRead(principal, assignmentId));
	}

	@Transactional
	public AssignmentResponse createAssignment(AuthenticatedPrincipal principal,
			UUID userId, UUID branchId) {
		User user = scopedUser(principal, userId);
		Branch branch = scopedBranch(principal, branchId);
		// A user without an organization holds no membership at any
		// organization and can never be staffed to a branch (ADR-040).
		if (user.getOrganization() == null
				|| !user.getOrganization().getId().equals(branch.getOrganization().getId())) {
			throw new StaffAssignmentBranchMismatchException(
					"User and branch belong to different organizations");
		}
		if (!user.getRoles().contains(RoleType.STAFF)) {
			throw new InvalidUserRequestException(
					"Only users with the STAFF role can receive branch assignments");
		}
		access.requireBranchManagement(principal, branchId);
		try {
			return AssignmentResponse.from(assignments.saveAndFlush(
					new StaffAssignment(user.getOrganization(), user, branch)));
		} catch (DataIntegrityViolationException e) {
			throw new StaffAssignmentConflictException(
					"User is already assigned to this branch — reactivate the assignment instead");
		}
	}

	@Transactional
	public AssignmentResponse activateAssignment(AuthenticatedPrincipal principal,
			UUID assignmentId) {
		StaffAssignment assignment = scopedAssignmentForManagement(principal, assignmentId);
		assignment.setStatus(StaffAssignmentStatus.ACTIVE);
		return AssignmentResponse.from(assignments.save(assignment));
	}

	@Transactional
	public AssignmentResponse deactivateAssignment(AuthenticatedPrincipal principal,
			UUID assignmentId) {
		StaffAssignment assignment = scopedAssignmentForManagement(principal, assignmentId);
		assignment.setStatus(StaffAssignmentStatus.INACTIVE);
		return AssignmentResponse.from(assignments.save(assignment));
	}

	private User scopedUser(AuthenticatedPrincipal principal, UUID userId) {
		User user = users.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"USER_NOT_FOUND", "User not found"));
		// Assignment surfaces are operational-only: customers reach the
		// policy (which denies them), so the equality only binds
		// operational principals.
		if (principal.isOperational()
				&& (user.getOrganization() == null
						|| !user.getOrganization().getId().equals(principal.organizationId()))) {
			throw new ResourceNotFoundException("USER_NOT_FOUND", "User not found");
		}
		return user;
	}

	private Branch scopedBranch(AuthenticatedPrincipal principal, UUID branchId) {
		Branch branch = branches.findById(branchId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"BRANCH_NOT_FOUND", "Branch not found"));
		// Assignments are operational-only: customers never reach the
		// assignment rule (the policy denies them with 403), so the
		// equality only ever binds operational principals.
		if (principal.isOperational()
				&& !branch.getOrganization().getId().equals(principal.organizationId())) {
			throw new ResourceNotFoundException("BRANCH_NOT_FOUND", "Branch not found");
		}
		return branch;
	}

	private StaffAssignment scopedAssignmentForRead(AuthenticatedPrincipal principal,
			UUID assignmentId) {
		StaffAssignment assignment = assignments.findById(assignmentId)
				.orElseThrow(() -> new StaffAssignmentNotFoundException("Assignment not found"));
		if (principal.isOperational()
				&& !assignment.getOrganization().getId().equals(principal.organizationId())) {
			throw new StaffAssignmentNotFoundException("Assignment not found");
		}
		access.requireBranchOperationalAccess(principal, assignment.getBranch().getId());
		return assignment;
	}

	private StaffAssignment scopedAssignmentForManagement(AuthenticatedPrincipal principal,
			UUID assignmentId) {
		StaffAssignment assignment = assignments.findById(assignmentId)
				.orElseThrow(() -> new StaffAssignmentNotFoundException("Assignment not found"));
		if (principal.isOperational()
				&& !assignment.getOrganization().getId().equals(principal.organizationId())) {
			throw new StaffAssignmentNotFoundException("Assignment not found");
		}
		access.requireBranchManagement(principal, assignment.getBranch().getId());
		return assignment;
	}

	private StaffAssignmentStatus parseStatus(String statusFilter) {
		try {
			return StaffAssignmentStatus.valueOf(statusFilter.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new InvalidRequestException("INVALID_STATUS",
					"Unsupported status filter: " + statusFilter
							+ ". Supported: ACTIVE, INACTIVE.");
		}
	}
}
