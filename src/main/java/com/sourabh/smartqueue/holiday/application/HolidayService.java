package com.sourabh.smartqueue.holiday.application;

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
import com.sourabh.smartqueue.holiday.Holiday;
import com.sourabh.smartqueue.holiday.HolidayConflictException;
import com.sourabh.smartqueue.holiday.HolidayRepository;
import com.sourabh.smartqueue.holiday.api.CreateHolidayRequest;
import com.sourabh.smartqueue.holiday.api.HolidayResponse;

/**
 * Branch holiday management (see ADR-010).
 *
 * <p>Same authorization shape as working hours: operational reads,
 * management writes, scope-safe 404s. Full-day closures only — no
 * partial days, no recurrences (deferred product decisions, not
 * missing validation). Duplicate dates translate to an honest
 * {@code 409}.
 */
@Service
public class HolidayService {

	private final HolidayRepository holidays;
	private final BranchRepository branches;
	private final TenantAccessPolicy access;

	public HolidayService(HolidayRepository holidays,
			BranchRepository branches,
			TenantAccessPolicy access) {
		this.holidays = holidays;
		this.branches = branches;
		this.access = access;
	}

	@Transactional(readOnly = true)
	public List<HolidayResponse> list(AuthenticatedPrincipal principal, UUID branchId) {
		Branch branch = scopedBranch(principal, branchId);
		access.requireBranchOperationalAccess(principal, branch.getId());
		return holidays.findByBranchIdOrderByDateAsc(branch.getId()).stream()
				.map(HolidayResponse::from)
				.toList();
	}

	@Transactional
	public HolidayResponse create(AuthenticatedPrincipal principal, UUID branchId,
			CreateHolidayRequest request) {
		Branch branch = scopedBranch(principal, branchId);
		access.requireBranchManagement(principal, branch.getId());
		if (request.name() == null || request.name().isBlank()) {
			throw new InvalidRequestException("INVALID_HOLIDAY", "A holiday name is required");
		}
		try {
			return HolidayResponse.from(holidays.saveAndFlush(
					new Holiday(branch, request.date(), request.name().trim())));
		} catch (DataIntegrityViolationException e) {
			throw new HolidayConflictException(
					"A holiday already exists on " + request.date() + " for this branch", e);
		}
	}

	@Transactional
	public void delete(AuthenticatedPrincipal principal, UUID holidayId) {
		Holiday holiday = holidays.findById(holidayId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"HOLIDAY_NOT_FOUND", "Holiday not found"));
		if (principal.isOperational()
				&& !holiday.getBranch().getOrganization().getId().equals(principal.organizationId())) {
			throw new ResourceNotFoundException("HOLIDAY_NOT_FOUND", "Holiday not found");
		}
		access.requireBranchManagement(principal, holiday.getBranch().getId());
		holidays.delete(holiday);
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
}


