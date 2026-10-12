package com.sourabh.smartqueue.holiday.api;

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
import com.sourabh.smartqueue.holiday.application.HolidayService;

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
 * Branch holiday collection (see ADR-010).
 *
 * <p>Reads are operational (managers/admins plus assigned staff);
 * creation needs branch management. Full-day closures only.
 * Thin by rule — HTTP binding plus delegation only.
 */
@RestController
@RequestMapping("/api/v1/branches/{branchId}/holidays")
@Tag(name = "Holidays", description = "Branch full-day closures")
@SecurityRequirement(name = "bearerAuth")
public class BranchHolidaysController {

	private final HolidayService holidays;

	public BranchHolidaysController(HolidayService holidays) {
		this.holidays = holidays;
	}

	@Operation(summary = "List holidays",
			description = "The branch's closures, date-ordered. Operational visibility: "
					+ "managers/admins plus assigned staff.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The holidays (empty when none)"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Outside operational scope"),
			@ApiResponse(responseCode = "404", description = "Unknown or foreign branch")})
	@GetMapping
	public ResponseEntity<List<HolidayResponse>> list(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id", required = true)
			@PathVariable UUID branchId) {
		return ResponseEntity.ok(holidays.list(principal, branchId));
	}

	@Operation(summary = "Add a holiday",
			description = "Branch management only. Duplicate dates fail with 409.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Created",
					content = @Content(schema = @Schema(implementation = HolidayResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid date or name"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Not branch management"),
			@ApiResponse(responseCode = "404", description = "Unknown or foreign branch"),
			@ApiResponse(responseCode = "409", description = "Holiday already exists on that date")})
	@PostMapping
	public ResponseEntity<HolidayResponse> create(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id", required = true)
			@PathVariable UUID branchId,
			@Valid @RequestBody CreateHolidayRequest request) {
		return ResponseEntity.ok(holidays.create(principal, branchId, request));
	}
}
