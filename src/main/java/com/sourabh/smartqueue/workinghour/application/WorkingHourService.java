package com.sourabh.smartqueue.workinghour.application;

import java.time.DayOfWeek;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.TenantAccessPolicy;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.CustomerScope;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.workinghour.WorkingHour;
import com.sourabh.smartqueue.workinghour.WorkingHourConflictException;
import com.sourabh.smartqueue.workinghour.WorkingHourRepository;
import com.sourabh.smartqueue.workinghour.api.CreateWorkingHourRequest;
import com.sourabh.smartqueue.workinghour.api.WorkingHourResponse;

/**
 * Branch operating-calendar management (see ADR-010).
 *
 * <p>Reads are operational (managers/admins plus assigned staff —
 * the same visibility as appointments and rosters); writes need
 * branch management. Intervals are immutable by domain design, so
 * this service offers create + delete only (no update endpoint
 * exists to drift half-edited). Overlap arbitration stays in the
 * database exclusion constraint — the pre-check would be a race;
 * its rejection translates to an honest {@code 409}.
 */
@Service
public class WorkingHourService {

	private final WorkingHourRepository workingHours;
	private final BranchRepository branches;
	private final TenantAccessPolicy access;

	public WorkingHourService(WorkingHourRepository workingHours,
			BranchRepository branches,
			TenantAccessPolicy access) {
		this.workingHours = workingHours;
		this.branches = branches;
		this.access = access;
	}

	@Transactional(readOnly = true)
	public List<WorkingHourResponse> list(AuthenticatedPrincipal principal, UUID branchId) {
		Branch branch = scopedBranch(principal, branchId);
		access.requireBranchOperationalAccess(principal, branch.getId());
		return workingHours.findByBranchId(branch.getId()).stream()
				.sorted(Comparator.comparing((WorkingHour w) -> w.getDayOfWeek().getValue())
						.thenComparing(WorkingHour::getStartTime))
				.map(WorkingHourResponse::from)
				.toList();
	}

	@Transactional
	public WorkingHourResponse create(AuthenticatedPrincipal principal, UUID branchId,
			CreateWorkingHourRequest request) {
		Branch branch = scopedBranch(principal, branchId);
		access.requireBranchManagement(principal, branch.getId());
		DayOfWeek day = parseDay(request.dayOfWeek());
		WorkingHour workingHour;
		try {
			workingHour = new WorkingHour(branch, day, request.startTime(), request.endTime());
		} catch (IllegalArgumentException e) {
			throw new InvalidRequestException("INVALID_WORKING_HOUR", e.getMessage());
		}
		try {
			return WorkingHourResponse.from(workingHours.saveAndFlush(workingHour));
		} catch (DataIntegrityViolationException e) {
			throw new WorkingHourConflictException(
					"Working hours overlap an existing interval on " + day, e);
		}
	}

	@Transactional
	public void delete(AuthenticatedPrincipal principal, UUID workingHourId) {
		WorkingHour workingHour = workingHours.findById(workingHourId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"WORKING_HOUR_NOT_FOUND", "Working hours not found"));
		if (principal.isOperational()
				&& !workingHour.getBranch().getOrganization().getId()
						.equals(principal.organizationId())) {
			throw new ResourceNotFoundException(
					"WORKING_HOUR_NOT_FOUND", "Working hours not found");
		}
		access.requireBranchManagement(principal, workingHour.getBranch().getId());
		workingHours.delete(workingHour);
	}

	private Branch scopedBranch(AuthenticatedPrincipal principal, UUID branchId) {
		Branch branch = branches.findById(branchId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"BRANCH_NOT_FOUND", "Branch not found"));
		// These are operational surfaces: customers reach the policy
		// (which denies them) through an ACTIVE-scope resolution.
		if (principal.isOperational()) {
			if (!branch.getOrganization().getId().equals(principal.organizationId())) {
				throw new ResourceNotFoundException("BRANCH_NOT_FOUND", "Branch not found");
			}
			return branch;
		}
		CustomerScope.requireActiveBranch(branch, "BRANCH_NOT_FOUND", "Branch not found");
		return branch;
	}

	private DayOfWeek parseDay(String dayOfWeek) {
		if (dayOfWeek == null) {
			throw new InvalidRequestException("INVALID_DAY",
					"Day of week is required (MONDAY–SUNDAY)");
		}
		try {
			return DayOfWeek.valueOf(dayOfWeek.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new InvalidRequestException("INVALID_DAY",
					"Unsupported day of week: " + dayOfWeek
							+ ". Supported: MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY.");
		}
	}
}


