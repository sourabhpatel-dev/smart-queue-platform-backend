package com.sourabh.smartqueue.branch.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.branch.application.BranchService;
import com.sourabh.smartqueue.branch.application.ScheduleStatusService;
import com.sourabh.smartqueue.branch.application.ScheduleStatusService.ScheduleStatusResponse;
import com.sourabh.smartqueue.common.api.ApiPage;
import com.sourabh.smartqueue.common.api.ApiPageRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;



/**
 * Branch REST boundary (see ADR-023).
 *
 * <p>First real management surface: scoped listing (paginated, allow-list
 * sorted, optional status filter), single read, creation (organization
 * from the principal, {@code 201} + {@code Location}), full-replacement
 * update (PUT only — no PATCH), and explicit activate/deactivate
 * commands. No deletion (status lifecycle instead). Thin by rule — HTTP
 * binding plus delegation; scope and roles live in the service and
 * {@link com.sourabh.smartqueue.auth.TenantAccessPolicy}.
 */
@RestController
@RequestMapping("/api/v1/branches")
@Tag(name = "Branches", description = "Branch management within the caller's organization")
@SecurityRequirement(name = "bearerAuth")
public class BranchController {

	private final BranchService branches;
	private final ScheduleStatusService scheduleStatusService;

	public BranchController(BranchService branches,
			ScheduleStatusService scheduleStatusService) {
		this.branches = branches;
		this.scheduleStatusService = scheduleStatusService;
	}

	@Operation(summary = "List the caller's organization branches",
			description = "Own organization only, paginated (page 0-based, size 1-100). "
					+ "Sort allow-list: name, status, createdAt, updatedAt "
					+ "as field[,asc|desc]; ties break by id. Optional status filter "
					+ "(ACTIVE/INACTIVE). Inactive branches are included unless filtered. "
					+ "Readable by every role in the organization.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The page (empty when none)"),
			@ApiResponse(responseCode = "400", description = "Invalid page/size/sort/status"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication")})
	@GetMapping
	@Parameters({
			@Parameter(name = "page", description = "Zero-based page index", example = "0"),
			@Parameter(name = "size", description = "Page size, 1-100", example = "20"),
			@Parameter(name = "sort",
					description = "Sort entries as field[,asc|desc]; repeatable "
							+ "(allow-list: name, status, createdAt, updatedAt)",
					example = "name,asc"),
			@Parameter(name = "status", description = "Optional status filter",
					example = "ACTIVE")})
	public ResponseEntity<ApiPage<BranchResponse>> listBranches(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String status,
			HttpServletRequest request) {
		return ResponseEntity.ok(branches.listBranches(principal, page, size,
				ApiPageRequest.rawSorts(request), status));
	}

	@Operation(summary = "Read a branch of the caller's organization",
			description = "Foreign-organization branches yield 404 (no enumeration); "
					+ "same-organization branches outside the caller's role yield 403.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The branch",
					content = @Content(schema = @Schema(implementation = BranchResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Insufficient branch authorization"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@GetMapping("/{branchId}")
	public ResponseEntity<BranchResponse> getBranch(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id within the caller's organization",
					required = true) @PathVariable UUID branchId) {
		return ResponseEntity.ok(branches.getBranch(principal, branchId));
	}

	@Operation(summary = "Create a branch in the caller's organization",
			description = "MANAGER or ADMIN only. The organization comes from the "
					+ "authenticated principal — request bodies carry no tenant ids. "
					+ "Branches are born ACTIVE; duplicate names in the organization "
					+ "yield 409 (same name elsewhere stays legal).")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Created",
					content = @Content(schema = @Schema(implementation = BranchResponse.class))),
			@ApiResponse(responseCode = "400", description = "Validation failure"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "409", description = "Duplicate branch name")})
	@PostMapping
	public ResponseEntity<BranchResponse> createBranch(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Valid @RequestBody CreateBranchRequest request) {
		BranchResponse created = branches.createBranch(principal, request);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{branchId}")
				.buildAndExpand(created.id())
				.toUri();
		return ResponseEntity.created(location).body(created);
	}

	@Operation(summary = "Replace a branch's mutable fields",
			description = "PUT carries the complete mutable representation (name, "
					+ "address, city, state, postal code, country, time zone) — "
					+ "no partial semantics, no PATCH variant. Organization ownership "
					+ "and status are immutable here: status moves through "
					+ "activate/deactivate only. MANAGER or ADMIN only.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Updated",
					content = @Content(schema = @Schema(implementation = BranchResponse.class))),
			@ApiResponse(responseCode = "400", description = "Validation failure"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization"),
			@ApiResponse(responseCode = "409", description = "Duplicate branch name")})
	@PutMapping("/{branchId}")
	public ResponseEntity<BranchResponse> updateBranch(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id within the caller's organization",
					required = true) @PathVariable UUID branchId,
			@Valid @RequestBody UpdateBranchRequest request) {
		return ResponseEntity.ok(branches.updateBranch(principal, branchId, request));
	}

	@Operation(summary = "Activate a branch",
			description = "Idempotent — activating an active branch succeeds unchanged. "
					+ "MANAGER or ADMIN only; foreign branches yield 404.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Activated",
					content = @Content(schema = @Schema(implementation = BranchResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@PostMapping("/{branchId}/activate")
	public ResponseEntity<BranchResponse> activateBranch(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id within the caller's organization",
					required = true) @PathVariable UUID branchId) {
		return ResponseEntity.ok(branches.activateBranch(principal, branchId));
	}

	@Operation(summary = "Deactivate a branch",
			description = "Status lifecycle, not deletion: dependent records (services, "
					+ "counters, assignments, calendar, appointments, queues) are never "
					+ "cascade-deleted and remain. Deactivated branches stay visible to "
					+ "list/get. Idempotent. MANAGER or ADMIN only; foreign branches "
					+ "yield 404.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Deactivated",
					content = @Content(schema = @Schema(implementation = BranchResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@PostMapping("/{branchId}/deactivate")
	public ResponseEntity<BranchResponse> deactivateBranch(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id within the caller's organization",
					required = true) @PathVariable UUID branchId) {
		return ResponseEntity.ok(branches.deactivateBranch(principal, branchId));
	}

	@Operation(summary = "Read a branch's schedule status (open/closed/holiday/inactive)",
			description = "Readable by every role in the organization. Returns the "
					+ "current state (OPEN, CLOSED, HOLIDAY, or INACTIVE), the branch's "
					+ "timezone, the current instant, today's operating intervals, and "
					+ "the next upcoming holiday (if any). Inactive branches return state "
					+ "INACTIVE rather than an error, per the project's display-first policy.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Schedule status",
					content = @Content(schema = @Schema(implementation = ScheduleStatusResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Insufficient branch authorization"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@GetMapping("/{branchId}/schedule-status")
	public ResponseEntity<ScheduleStatusResponse> getScheduleStatus(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id within the caller's organization",
					required = true) @PathVariable UUID branchId) {
		ScheduleStatusResponse status = scheduleStatusService.getScheduleStatus(principal, branchId);
		return ResponseEntity.ok(status);
	}
}
