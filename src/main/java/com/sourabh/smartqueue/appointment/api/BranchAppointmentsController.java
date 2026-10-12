package com.sourabh.smartqueue.appointment.api;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
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

import com.sourabh.smartqueue.appointment.application.AppointmentService;
import com.sourabh.smartqueue.appointment.AppointmentAvailabilityService;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Branch-scoped booking boundary (see ADR-026).
 *
 * <p>Creation nests under the owning branch so the branch context is
 * explicit; the service must belong to it (same-org mismatch → 422,
 * foreign rows → 404). The customer is always the principal — every role
 * books for itself, no delegated booking exists. Thin by rule: shape +
 * scope here, calendar/DST/overlap arbitration inside the booking
 * service. Availability (ADR-039) nests here too: same scope, read
 * only, advisory — booking re-arbitrates authoritatively.
 */
@RestController
@RequestMapping("/api/v1/branches/{branchId}/appointments")
@Tag(name = "Appointments", description = "Branch appointment booking and lifecycle")
@SecurityRequirement(name = "bearerAuth")
public class BranchAppointmentsController {

	private final AppointmentService appointments;
	private final AppointmentAvailabilityService availability;

	public BranchAppointmentsController(AppointmentService appointments,
			AppointmentAvailabilityService availability) {
		this.appointments = appointments;
		this.availability = availability;
	}

	@Operation(summary = "List bookable appointment starts for a branch-local date",
			description = "Advisory read-only availability for the caller's own "
					+ "booking: branch-local date in the branch timezone, "
					+ "holiday veto, whole-interval fit, strict-future starts, "
					+ "and live service/customer conflicts — one-minute "
					+ "candidate steps (the domain's own granularity, not a "
					+ "slot grid). A returned slot may lose a concurrent race; "
					+ "booking re-arbitrates with 409. Inactive resources "
					+ "expose no slots (400, same as booking).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The day's availability",
					content = @Content(schema = @Schema(implementation = AvailabilityResponse.class))),
			@ApiResponse(responseCode = "400", description = "Bad date/service, inactive branch/service/customer"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "404", description = "Unknown branch/service or foreign organization"),
			@ApiResponse(responseCode = "422", description = "Service belongs to another branch")})
	@GetMapping("/availability")
	public ResponseEntity<AvailabilityResponse> availability(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id within the caller's organization",
					required = true) @PathVariable UUID branchId,
			@Parameter(description = "Service id within the branch", required = true)
			@RequestParam UUID serviceId,
			@Parameter(description = "Branch-local calendar date (YYYY-MM-DD)",
					required = true, example = "2026-10-20")
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return ResponseEntity.ok(availability.availability(principal, branchId, serviceId, date));
	}

	@Operation(summary = "Book an appointment at the branch",
			description = "Any authenticated role books for itself (customer id is "
					+ "the principal, never the body). The service must belong to "
					+ "the path branch. Working hours, holidays, DST, duration "
					+ "snapshot, and overlap arbitration follow the existing "
					+ "booking rules; conflicts yield 409 with the slot intact.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Booked",
					content = @Content(schema = @Schema(implementation = AppointmentResponse.class))),
			@ApiResponse(responseCode = "400", description = "Validation failure, inactive branch/service/customer, outside hours, holiday, past start"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "404", description = "Unknown branch/service or foreign organization"),
			@ApiResponse(responseCode = "409", description = "Slot conflicts with an existing appointment"),
			@ApiResponse(responseCode = "422", description = "Service belongs to another branch")})
	@PostMapping
	public ResponseEntity<AppointmentResponse> bookAppointment(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id within the caller's organization",
					required = true) @PathVariable UUID branchId,
			@Valid @RequestBody CreateAppointmentRequest request) {
		AppointmentResponse booked = appointments.book(principal, branchId, request);
		URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
				.path("/api/v1/appointments/{appointmentId}")
				.buildAndExpand(booked.id())
				.toUri();
		return ResponseEntity.created(location).body(booked);
	}
}
