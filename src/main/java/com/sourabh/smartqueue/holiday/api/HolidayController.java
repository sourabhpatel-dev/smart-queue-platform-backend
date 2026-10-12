package com.sourabh.smartqueue.holiday.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.holiday.application.HolidayService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Holiday item boundary: removal only. Branch management only.
 */
@RestController
@RequestMapping("/api/v1/holidays")
@Tag(name = "Holidays", description = "Branch full-day closures")
@SecurityRequirement(name = "bearerAuth")
public class HolidayController {

	private final HolidayService holidays;

	public HolidayController(HolidayService holidays) {
		this.holidays = holidays;
	}

	@Operation(summary = "Delete a holiday",
			description = "Branch management only. Unknown or foreign ids are scope-safe 404s.")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Deleted"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Not branch management"),
			@ApiResponse(responseCode = "404", description = "Unknown or foreign holiday")})
	@DeleteMapping("/{holidayId}")
	public ResponseEntity<Void> delete(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Holiday id", required = true)
			@PathVariable UUID holidayId) {
		holidays.delete(principal, holidayId);
		return ResponseEntity.noContent().build();
	}
}
