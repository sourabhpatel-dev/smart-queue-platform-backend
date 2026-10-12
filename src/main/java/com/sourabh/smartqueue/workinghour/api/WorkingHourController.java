package com.sourabh.smartqueue.workinghour.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.workinghour.application.WorkingHourService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Working-hour item boundary: removal only (intervals are immutable
 * — correct by deleting and recreating). Branch management only.
 */
@RestController
@RequestMapping("/api/v1/working-hours")
@Tag(name = "Working hours", description = "Branch operating intervals")
@SecurityRequirement(name = "bearerAuth")
public class WorkingHourController {

	private final WorkingHourService workingHours;

	public WorkingHourController(WorkingHourService workingHours) {
		this.workingHours = workingHours;
	}

	@Operation(summary = "Delete a working-hour interval",
			description = "Branch management only. Unknown or foreign ids are scope-safe 404s.")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Deleted"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Not branch management"),
			@ApiResponse(responseCode = "404", description = "Unknown or foreign interval")})
	@DeleteMapping("/{workingHourId}")
	public ResponseEntity<Void> delete(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Working-hour id", required = true)
			@PathVariable UUID workingHourId) {
		workingHours.delete(principal, workingHourId);
		return ResponseEntity.noContent().build();
	}
}
