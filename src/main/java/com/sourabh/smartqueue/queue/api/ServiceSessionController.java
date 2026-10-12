package com.sourabh.smartqueue.queue.api;

import java.time.Instant;
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
import com.sourabh.smartqueue.queue.application.ServiceSessionService;

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
 * Service-session read boundary (see ADR-027, session reads).
 *
 * <p>Operational visibility only — no lifecycle commands exist here
 * (the 3L entry commands remain the sole authority). Staff name an
 * assigned branch; managers/admins roam with scope-checked facets;
 * customers have no session views. Thin by rule.
 */
@RestController
@RequestMapping("/api/v1/service-sessions")
@Tag(name = "Service sessions", description = "Measured service interactions")
@SecurityRequirement(name = "bearerAuth")
public class ServiceSessionController {

	private final ServiceSessionService sessions;

	public ServiceSessionController(ServiceSessionService sessions) {
		this.sessions = sessions;
	}

	@Operation(summary = "List service sessions",
			description = "Operational roster of measured interactions, newest "
					+ "first by default. Staff must name an assigned branchId; "
					+ "managers/admins combine branchId, counterId, serviceId, "
					+ "customerId, queueEntryId (each scope-checked, 404 when "
					+ "foreign), active flag, and a startedAt from/to window. "
					+ "Paginated (page 0-based, size 1-100); sort allow-list: "
					+ "startedAt, endedAt, createdAt, updatedAt; ties break by id.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The page (empty when none)"),
			@ApiResponse(responseCode = "400", description = "Invalid page/size/sort/filter/range"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Operational-only for this caller")})
	@Parameters({
			@Parameter(name = "page", description = "Zero-based page index", example = "0"),
			@Parameter(name = "size", description = "Page size, 1-100", example = "20"),
			@Parameter(name = "sort",
					description = "Sort entries as field[,asc|desc]; repeatable",
					example = "startedAt,desc"),
			@Parameter(name = "branchId", description = "Optional branch facet",
					example = "00000000-0000-0000-0000-000000000000"),
			@Parameter(name = "counterId", description = "Optional counter facet",
					example = "00000000-0000-0000-0000-000000000000"),
			@Parameter(name = "serviceId", description = "Optional service facet",
					example = "00000000-0000-0000-0000-000000000000"),
			@Parameter(name = "customerId", description = "Optional customer facet",
					example = "00000000-0000-0000-0000-000000000000"),
			@Parameter(name = "queueEntryId", description = "Optional entry facet",
					example = "00000000-0000-0000-0000-000000000000"),
			@Parameter(name = "active", description = "Running only (true) or ended only (false)",
					example = "true"),
			@Parameter(name = "from", description = "Started-at window start, ISO-8601",
					example = "2026-10-07T00:00:00Z"),
			@Parameter(name = "to", description = "Started-at window end, ISO-8601",
					example = "2026-10-08T00:00:00Z")})
	@GetMapping
	public ResponseEntity<ApiPage<ServiceSessionResponse>> listSessions(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) UUID branchId,
			@RequestParam(required = false) UUID counterId,
			@RequestParam(required = false) UUID serviceId,
			@RequestParam(required = false) UUID customerId,
			@RequestParam(required = false) UUID queueEntryId,
			@RequestParam(required = false) Boolean active,
			@RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to,
			HttpServletRequest request) {
		return ResponseEntity.ok(sessions.listSessions(principal, page, size,
				ApiPageRequest.rawSorts(request), branchId, counterId, serviceId,
				customerId, queueEntryId, active, from, to));
	}

	@Operation(summary = "Read a service session",
			description = "Operational visibility: managers/admins plus staff "
					+ "assigned to the branch. Everyone else — including "
					+ "same-organization customers — gets the scope-safe 404, "
					+ "never a peek at another customer's session.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The session",
					content = @Content(schema = @Schema(implementation = ServiceSessionResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "404", description = "Unknown id, foreign organization, or not visible")})
	@GetMapping("/{sessionId}")
	public ResponseEntity<ServiceSessionResponse> getSession(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Session id", required = true)
			@PathVariable UUID sessionId) {
		return ResponseEntity.ok(sessions.getSession(principal, sessionId));
	}
}
