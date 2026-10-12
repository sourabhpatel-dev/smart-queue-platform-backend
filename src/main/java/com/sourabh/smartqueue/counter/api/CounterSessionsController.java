package com.sourabh.smartqueue.counter.api;

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
import com.sourabh.smartqueue.queue.api.ServiceSessionResponse;
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
 * Counter operational-state boundary (see ADR-027, session reads).
 *
 * <p>Answers "what is this counter serving right now" and "what has it
 * served", both from authoritative PostgreSQL state — never Redis, never
 * snapshots. An idle counter has no current session (scope-safe 404,
 * which doubles as the derived occupancy signal); history paginates
 * newest-first. Operational visibility only. Thin by rule.
 */
@RestController
@RequestMapping("/api/v1/counters/{counterId}")
@Tag(name = "Service sessions", description = "Measured service interactions")
@SecurityRequirement(name = "bearerAuth")
public class CounterSessionsController {

	private final ServiceSessionService sessions;

	public CounterSessionsController(ServiceSessionService sessions) {
		this.sessions = sessions;
	}

	@Operation(summary = "Read a counter's current session",
			description = "The running session ({@code endedAt} null, newest "
					+ "wins), or 404 when the counter is idle — the 404 doubles "
					+ "as the derived occupancy signal. No persisted "
					+ "busy/available flag exists; nothing is duplicated.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The running session",
					content = @Content(schema = @Schema(implementation = ServiceSessionResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Outside operational scope"),
			@ApiResponse(responseCode = "404", description = "Unknown counter, foreign organization, or idle")})
	@GetMapping("/current-session")
	public ResponseEntity<ServiceSessionResponse> getCurrentSession(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Counter id", required = true)
			@PathVariable UUID counterId) {
		return ResponseEntity.ok(sessions.getCurrentSession(principal, counterId));
	}

	@Operation(summary = "List a counter's session history",
			description = "Paginated, newest first by default. Sort allow-list: "
					+ "startedAt, endedAt, createdAt, updatedAt; ties break by id. "
					+ "History is never deleted — completed rows stay listed.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The page (empty when none)"),
			@ApiResponse(responseCode = "400", description = "Invalid page/size/sort"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Outside operational scope"),
			@ApiResponse(responseCode = "404", description = "Unknown counter or foreign organization")})
	@Parameters({
			@Parameter(name = "page", description = "Zero-based page index", example = "0"),
			@Parameter(name = "size", description = "Page size, 1-100", example = "20"),
			@Parameter(name = "sort",
					description = "Sort entries as field[,asc|desc]; repeatable",
					example = "startedAt,desc")})
	@GetMapping("/service-sessions")
	public ResponseEntity<ApiPage<ServiceSessionResponse>> listCounterSessions(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Counter id", required = true)
			@PathVariable UUID counterId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			HttpServletRequest request) {
		return ResponseEntity.ok(sessions.listCounterSessions(principal, counterId, page,
				size, ApiPageRequest.rawSorts(request)));
	}
}
