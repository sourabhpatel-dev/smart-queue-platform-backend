package com.sourabh.smartqueue.staff.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.staff.application.StaffAssignmentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Assignment item boundary (see ADR-025).
 *
 * <p>Single-assignment reads plus the idempotent activate/deactivate
 * commands — deactivation flips the status, never deletes the row, never
 * touches the user or the branch. Thin by rule.
 */
@RestController
@RequestMapping("/api/v1/staff-assignments")
@Tag(name = "Staff assignments", description = "Branch staffing links")
@SecurityRequirement(name = "bearerAuth")
public class StaffAssignmentController {

	private final StaffAssignmentService assignments;

	public StaffAssignmentController(StaffAssignmentService assignments) {
		this.assignments = assignments;
	}

	@Operation(summary = "Read an assignment",
			description = "Operational visibility: managers/admins plus staff "
					+ "assigned to the branch. Unknown-or-foreign ids yield 404.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The assignment",
					content = @Content(schema = @Schema(implementation = AssignmentResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Insufficient authorization"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@GetMapping("/{assignmentId}")
	public ResponseEntity<AssignmentResponse> getAssignment(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Assignment id", required = true)
			@PathVariable UUID assignmentId) {
		return ResponseEntity.ok(assignments.getAssignment(principal, assignmentId));
	}

	@Operation(summary = "Activate an assignment",
			description = "Idempotent — the path back after deactivation (no second "
					+ "row: the unique key ignores status). MANAGER or ADMIN with "
					+ "branch management authority; foreign rows yield 404.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Activated",
					content = @Content(schema = @Schema(implementation = AssignmentResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires branch management authority"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@PostMapping("/{assignmentId}/activate")
	public ResponseEntity<AssignmentResponse> activateAssignment(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Assignment id", required = true)
			@PathVariable UUID assignmentId) {
		return ResponseEntity.ok(assignments.activateAssignment(principal, assignmentId));
	}

	@Operation(summary = "Deactivate an assignment",
			description = "Status lifecycle, not deletion: the user, the branch, "
					+ "and the history stay intact — the STAFF holder simply loses "
					+ "that branch's access. Idempotent. MANAGER or ADMIN with "
					+ "branch management authority.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Deactivated",
					content = @Content(schema = @Schema(implementation = AssignmentResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires branch management authority"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@PostMapping("/{assignmentId}/deactivate")
	public ResponseEntity<AssignmentResponse> deactivateAssignment(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Assignment id", required = true)
			@PathVariable UUID assignmentId) {
		return ResponseEntity.ok(assignments.deactivateAssignment(principal, assignmentId));
	}
}
