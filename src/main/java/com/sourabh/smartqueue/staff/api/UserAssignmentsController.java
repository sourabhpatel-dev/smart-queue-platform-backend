package com.sourabh.smartqueue.staff.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

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
import jakarta.validation.Valid;

/**
 * Per-user assignment collection boundary (see ADR-025).
 *
 * <p>Listing and creation nest under the user so both linkage legs are
 * explicit. Reads allow the subject themself plus managers/admins;
 * creation needs branch management authority. Thin by rule.
 */
@RestController
@RequestMapping("/api/v1/users/{userId}/staff-assignments")
@Tag(name = "Staff assignments", description = "Branch staffing links")
@SecurityRequirement(name = "bearerAuth")
public class UserAssignmentsController {

	private final StaffAssignmentService assignments;

	public UserAssignmentsController(StaffAssignmentService assignments) {
		this.assignments = assignments;
	}

	@Operation(summary = "List a user's branch assignments",
			description = "The subject themself, or MANAGER/ADMIN. Paginated "
					+ "(page 0-based, size 1-100); sort allow-list: email, status, "
					+ "createdAt, updatedAt; optional status filter. History is "
					+ "preserved — deactivated rows stay listed unless filtered.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The page (empty when none)"),
			@ApiResponse(responseCode = "400", description = "Invalid page/size/sort/status"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Insufficient authorization"),
			@ApiResponse(responseCode = "404", description = "Unknown user or foreign organization")})
	@Parameters({
			@Parameter(name = "page", description = "Zero-based page index", example = "0"),
			@Parameter(name = "size", description = "Page size, 1-100", example = "20"),
			@Parameter(name = "sort",
					description = "Sort entries as field[,asc|desc]; repeatable "
							+ "(allow-list: email, status, createdAt, updatedAt)",
					example = "createdAt,desc"),
			@Parameter(name = "status", description = "Optional status filter",
					example = "ACTIVE")})
	@GetMapping
	public ResponseEntity<ApiPage<AssignmentResponse>> listUserAssignments(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "User id", required = true) @PathVariable UUID userId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String status,
			HttpServletRequest request) {
		return ResponseEntity.ok(assignments.listUserAssignments(principal, userId, page,
				size, ApiPageRequest.rawSorts(request), status));
	}

	@Operation(summary = "Assign the user to a branch",
			description = "MANAGER or ADMIN with branch management authority. The "
					+ "user comes from the path and must hold the STAFF role; the "
					+ "branch comes from the body and must live in the caller's "
					+ "organization (anything else is 404, cross-branch pairs are "
					+ "422). Re-adding an existing row yields 409 — reactivate it "
					+ "instead.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Assigned"),
			@ApiResponse(responseCode = "400", description = "Validation failure / ineligible role"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires branch management authority"),
			@ApiResponse(responseCode = "404", description = "Unknown user/branch or foreign organization"),
			@ApiResponse(responseCode = "409", description = "Assignment already exists"),
			@ApiResponse(responseCode = "422", description = "User and branch span organizations")})
	@PostMapping
	public ResponseEntity<AssignmentResponse> createAssignment(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "User id", required = true) @PathVariable UUID userId,
			@Valid @RequestBody CreateAssignmentRequest request) {
		AssignmentResponse created =
				assignments.createAssignment(principal, userId, request.branchId());
		URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
				.path("/api/v1/staff-assignments/{assignmentId}")
				.buildAndExpand(created.id())
				.toUri();
		return ResponseEntity.created(location).body(created);
	}
}
