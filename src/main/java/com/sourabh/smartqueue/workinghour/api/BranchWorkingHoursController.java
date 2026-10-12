package com.sourabh.smartqueue.workinghour.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.workinghour.application.WorkingHourService;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Branch operating-calendar collection (see ADR-010).
 *
 * <p>Reads are operational (managers/admins plus assigned staff);
 * creation needs branch management. Intervals are immutable —
 * corrections are delete + recreate via {@code WorkingHourController}.
 * Thin by rule — HTTP binding plus delegation only.
 */
@RestController
@RequestMapping("/api/v1/branches/{branchId}/working-hours")
@Tag(name = "Working hours", description = "Branch operating intervals")
@SecurityRequirement(name = "bearerAuth")
public class BranchWorkingHoursController {

	private final WorkingHourService workingHours;

	public BranchWorkingHoursController(WorkingHourService workingHours) {
		this.workingHours = workingHours;
	}

	@Operation(summary = "List working hours",
			description = "All intervals of the branch, weekday-ordered. Operational "
					+ "visibility: managers/admins plus assigned staff.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The intervals (empty when closed all week)"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Outside operational scope"),
			@ApiResponse(responseCode = "404", description = "Unknown or foreign branch")})
	@GetMapping
	public ResponseEntity<List<WorkingHourResponse>> list(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id", required = true)
			@PathVariable UUID branchId) {
		return ResponseEntity.ok(workingHours.list(principal, branchId));
	}

	@Operation(summary = "Add a working-hour interval",
			description = "Branch management only. Overlapping intervals fail with 409; "
					+ "adjacent intervals are allowed.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Created",
					content = @Content(schema = @Schema(implementation = WorkingHourResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid day, range, or granularity"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Not branch management"),
			@ApiResponse(responseCode = "404", description = "Unknown or foreign branch"),
			@ApiResponse(responseCode = "409", description = "Overlaps an existing interval")})
	@PostMapping
	public ResponseEntity<WorkingHourResponse> create(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id", required = true)
			@PathVariable UUID branchId,
			@Valid @RequestBody CreateWorkingHourRequest request) {
		return ResponseEntity.ok(workingHours.create(principal, branchId, request));
	}
}
