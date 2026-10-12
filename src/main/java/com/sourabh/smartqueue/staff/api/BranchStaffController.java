package com.sourabh.smartqueue.staff.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.common.api.ApiPage;
import com.sourabh.smartqueue.common.api.ApiPageRequest;
import com.sourabh.smartqueue.staff.application.StaffAssignmentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Branch roster boundary (see ADR-025).
 *
 * <p>Who staffs a branch, as bounded roster rows (user identity plus both
 * lifecycle states, no credentials). Operational visibility — not the
 * customer display rule: managers/admins org-wide plus staff assigned to
 * that branch; customers and unassigned staff are denied. Thin by rule.
 */
@RestController
@RequestMapping("/api/v1/branches/{branchId}/staff")
@Tag(name = "Staff assignments", description = "Branch staffing links")
@SecurityRequirement(name = "bearerAuth")
public class BranchStaffController {

	private final StaffAssignmentService assignments;

	public BranchStaffController(StaffAssignmentService assignments) {
		this.assignments = assignments;
	}

	@Operation(summary = "List a branch's staff roster",
			description = "Own organization only, paginated (page 0-based, size 1-100). "
					+ "Sort allow-list: email, status, createdAt, updatedAt as "
					+ "field[,asc|desc]; ties break by id. Optional status filter. "
					+ "Managers/admins plus staff assigned to the branch; customers "
					+ "and unassigned staff yield 403.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The page (empty when none)"),
			@ApiResponse(responseCode = "400", description = "Invalid page/size/sort/status"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Roster not visible to the caller"),
			@ApiResponse(responseCode = "404", description = "Unknown branch or foreign organization")})
	@Parameters({
			@Parameter(name = "page", description = "Zero-based page index", example = "0"),
			@Parameter(name = "size", description = "Page size, 1-100", example = "20"),
			@Parameter(name = "sort",
					description = "Sort entries as field[,asc|desc]; repeatable "
							+ "(allow-list: email, status, createdAt, updatedAt)",
					example = "email,asc"),
			@Parameter(name = "status", description = "Optional status filter",
					example = "ACTIVE")})
	@GetMapping
	public ResponseEntity<ApiPage<BranchStaffResponse>> listBranchStaff(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id within the caller's organization",
					required = true) @PathVariable UUID branchId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String status,
			HttpServletRequest request) {
		return ResponseEntity.ok(assignments.listBranchStaff(principal, branchId, page,
				size, ApiPageRequest.rawSorts(request), status));
	}
}
