package com.sourabh.smartqueue.queue.api;

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
import com.sourabh.smartqueue.queue.application.QueueService;

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
 * Queue-entry item boundary (see ADR-027).
 *
 * <p>Privacy-first reads plus the explicit domain commands — no generic
 * status writes. Commands delegate to the guarded engine transactions
 * (claim/lock/session atomicity preserved); refusals are honest
 * {@code 409}s, never silent successes. Thin by rule.
 */
@RestController
@RequestMapping("/api/v1/queue-entries/{entryId}")
@Tag(name = "Queues", description = "Walk-in queue operations")
@SecurityRequirement(name = "bearerAuth")
public class QueueEntryController {

	private final QueueService queues;

	public QueueEntryController(QueueService queues) {
		this.queues = queues;
	}

	@Operation(summary = "Read a queue entry",
			description = "The owner and operationally authorized staff/managers "
					+ "see the row with its live position and wait; everyone else "
					+ "— including same-organization customers — gets the scope-safe "
					+ "404, never a peek at another customer's entry.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The entry",
					content = @Content(schema = @Schema(implementation = QueueEntryResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "404", description = "Unknown id, foreign organization, or not visible")})
	@GetMapping
	public ResponseEntity<QueueEntryResponse> getEntry(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Entry id", required = true) @PathVariable UUID entryId) {
		return ResponseEntity.ok(queues.getEntry(principal, entryId));
	}

	@Operation(summary = "Start serving an entry",
			description = "CALLED → SERVING with the service session created "
					+ "atomically (the session stays the authoritative "
					+ "operational record). The counter must be active, at the "
					+ "entry's branch, and capable of its service. Operational "
					+ "scope only — customers cannot start service, even their own.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Serving",
					content = @Content(schema = @Schema(implementation = QueueEntryResponse.class))),
			@ApiResponse(responseCode = "400", description = "Inactive/incapable/cross-branch counter"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Outside operational scope"),
			@ApiResponse(responseCode = "404", description = "Unknown entry/counter or foreign organization"),
			@ApiResponse(responseCode = "409", description = "Entry not CALLED / session exists")})
	@PostMapping("/start-serving")
	public ResponseEntity<QueueEntryResponse> startServing(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Entry id", required = true) @PathVariable UUID entryId,
			@Valid @RequestBody StartServingRequest request) {
		return ResponseEntity.ok(
				queues.startServing(principal, entryId, request.counterId()));
	}

	@Operation(summary = "Complete a served entry",
			description = "SERVING → COMPLETED with the session ended "
					+ "atomically. Operational scope only. Refused on other "
					+ "states (409, not idempotent — the domain rule wins).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Completed",
					content = @Content(schema = @Schema(implementation = QueueEntryResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Outside operational scope"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization"),
			@ApiResponse(responseCode = "409", description = "Entry not SERVING")})
	@PostMapping("/complete")
	public ResponseEntity<QueueEntryResponse> complete(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Entry id", required = true) @PathVariable UUID entryId) {
		return ResponseEntity.ok(queues.completeEntry(principal, entryId));
	}

	@Operation(summary = "Cancel a queue entry",
			description = "The owner or branch management (MANAGER/ADMIN); staff "
					+ "front-desk cancellation for others is deferred. History "
					+ "preserved — the freed active slot re-joins immediately. "
					+ "Refused on terminal states (409).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Cancelled",
					content = @Content(schema = @Schema(implementation = QueueEntryResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Neither owner nor branch management"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization"),
			@ApiResponse(responseCode = "409", description = "Already terminal")})
	@PostMapping("/cancel")
	public ResponseEntity<QueueEntryResponse> cancel(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Entry id", required = true) @PathVariable UUID entryId) {
		return ResponseEntity.ok(queues.cancelEntry(principal, entryId));
	}

	@Operation(summary = "Mark a queue entry as no-show",
			description = "WAITING/CALLED → NO_SHOW, freeing the active slot "
					+ "with no requeue side effects. Operational scope only. "
					+ "Refused on other states (409).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Marked no-show",
					content = @Content(schema = @Schema(implementation = QueueEntryResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Outside operational scope"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization"),
			@ApiResponse(responseCode = "409", description = "Already terminal/serving")})
	@PostMapping("/no-show")
	public ResponseEntity<QueueEntryResponse> markNoShow(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Entry id", required = true) @PathVariable UUID entryId) {
		return ResponseEntity.ok(queues.markNoShow(principal, entryId));
	}
}
