package com.sourabh.smartqueue.appointment.api;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sourabh.smartqueue.appointment.application.AppointmentService;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
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

/**
 * Appointment item + listing boundary (see ADR-026).
 *
 * <p>Reads are privacy-first (owners and operationally authorized
 * staff/managers; everyone else gets the scope-safe {@code 404}).
 * Listing is role-routed: customers pinned to themselves, staff naming
 * an assigned branch, managers/admins roaming their organization with
 * optional facets. Lifecycle commands call the guarded domain
 * transitions (refusals are {@code 409}, deliberately not idempotent).
 * Thin by rule — HTTP binding plus delegation only.
 */
@RestController
@RequestMapping("/api/v1/appointments")
@Tag(name = "Appointments", description = "Branch appointment booking and lifecycle")
@SecurityRequirement(name = "bearerAuth")
public class AppointmentController {

	private final AppointmentService appointments;

	public AppointmentController(AppointmentService appointments) {
		this.appointments = appointments;
	}

	@Operation(summary = "List appointments",
			description = "Role-routed: customers see only themselves (naming another "
					+ "customerId is 403); staff must name an assigned branchId; "
					+ "managers/admins roam the organization with optional branchId, "
					+ "serviceId, customerId facets (each scope-checked, 404 when "
					+ "foreign). Time window selects overlapping intervals "
					+ "(endAt > from AND startAt < to), ISO-8601. Paginated "
					+ "(page 0-based, size 1-100); sort allow-list: startAt (default), "
					+ "endAt, status, createdAt, updatedAt; ties break by id.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The page (empty when none)"),
			@ApiResponse(responseCode = "400", description = "Invalid page/size/sort/filter/range"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Customer naming another customer")})
	@Parameters({
			@Parameter(name = "page", description = "Zero-based page index", example = "0"),
			@Parameter(name = "size", description = "Page size, 1-100", example = "20"),
			@Parameter(name = "sort",
					description = "Sort entries as field[,asc|desc]; repeatable",
					example = "startAt,asc"),
			@Parameter(name = "status", description = "Optional status filter",
					example = "SCHEDULED"),
			@Parameter(name = "branchId", description = "Optional branch facet",
					example = "00000000-0000-0000-0000-000000000000"),
			@Parameter(name = "serviceId", description = "Optional service facet",
					example = "00000000-0000-0000-0000-000000000000"),
			@Parameter(name = "customerId", description = "Optional customer facet",
					example = "00000000-0000-0000-0000-000000000000"),
			@Parameter(name = "from", description = "Window start, ISO-8601",
					example = "2026-10-07T00:00:00Z"),
			@Parameter(name = "to", description = "Window end, ISO-8601",
					example = "2026-10-08T00:00:00Z")})
	@GetMapping
	public ResponseEntity<ApiPage<AppointmentResponse>> listAppointments(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) UUID branchId,
			@RequestParam(required = false) UUID serviceId,
			@RequestParam(required = false) UUID customerId,
			@RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to,
			HttpServletRequest request) {
		return ResponseEntity.ok(appointments.listAppointments(principal, page, size,
				ApiPageRequest.rawSorts(request), status, branchId, serviceId, customerId,
				from, to));
	}

	@Operation(summary = "Read an appointment",
			description = "The owner and operationally authorized staff/managers "
					+ "see the row; everyone else — including same-organization "
					+ "customers and unassigned staff — gets the scope-safe 404, "
					+ "never a peek at another customer's appointment.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The appointment",
					content = @Content(schema = @Schema(implementation = AppointmentResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "404", description = "Unknown id, foreign organization, or not visible")})
	@GetMapping("/{appointmentId}")
	public ResponseEntity<AppointmentResponse> getAppointment(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Appointment id", required = true)
			@PathVariable UUID appointmentId) {
		return ResponseEntity.ok(appointments.getAppointment(principal, appointmentId));
	}

	@Operation(summary = "Cancel an appointment",
			description = "The owner or branch management (MANAGER/ADMIN); staff "
					+ "front-desk cancellation is deferred. History preserved — "
					+ "the freed slot re-books immediately. Refused on terminal "
					+ "states (409, not idempotent — the domain rule wins).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Cancelled",
					content = @Content(schema = @Schema(implementation = AppointmentResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Neither owner nor branch management"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization"),
			@ApiResponse(responseCode = "409", description = "Already terminal")})
	@PostMapping("/{appointmentId}/cancel")
	public ResponseEntity<AppointmentResponse> cancelAppointment(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Appointment id", required = true)
			@PathVariable UUID appointmentId) {
		return ResponseEntity.ok(appointments.cancelAppointment(principal, appointmentId));
	}

	@Operation(summary = "Complete an appointment",
			description = "Operational outcome: assigned staff at the branch plus "
					+ "managers/admins. Refused on terminal states (409).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Completed",
					content = @Content(schema = @Schema(implementation = AppointmentResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Outside operational scope"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization"),
			@ApiResponse(responseCode = "409", description = "Already terminal")})
	@PostMapping("/{appointmentId}/complete")
	public ResponseEntity<AppointmentResponse> completeAppointment(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Appointment id", required = true)
			@PathVariable UUID appointmentId) {
		return ResponseEntity.ok(appointments.completeAppointment(principal, appointmentId));
	}

	@Operation(summary = "Mark an appointment as no-show",
			description = "Operational outcome: assigned staff at the branch plus "
					+ "managers/admins. The slot frees (exclusions ignore terminal "
					+ "states). Refused on terminal states (409).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Marked no-show",
					content = @Content(schema = @Schema(implementation = AppointmentResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Outside operational scope"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization"),
			@ApiResponse(responseCode = "409", description = "Already terminal")})
	@PostMapping("/{appointmentId}/no-show")
	public ResponseEntity<AppointmentResponse> markNoShow(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Appointment id", required = true)
			@PathVariable UUID appointmentId) {
		return ResponseEntity.ok(appointments.markNoShow(principal, appointmentId));
	}
}
