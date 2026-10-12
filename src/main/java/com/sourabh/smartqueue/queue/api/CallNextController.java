package com.sourabh.smartqueue.queue.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

/**
 * Counter-driven claim boundary (see ADR-027).
 *
 * <p>Deliberately counter-centric, not queue-centric: the atomic claim
 * selects across every queue the counter may serve, so the endpoint
 * names the counter and returns whatever the claim selected (with its
 * queue id). Scoping the path to one queue would misrepresent the
 * operation — a claim that legally selects a sibling queue's entry
 * cannot be "put back". Operational scope at the counter's branch only.
 */
@RestController
@RequestMapping("/api/v1/counters/{counterId}/call-next")
@Tag(name = "Queues", description = "Walk-in queue operations")
@SecurityRequirement(name = "bearerAuth")
public class CallNextController {

	private final QueueService queues;

	public CallNextController(QueueService queues) {
		this.queues = queues;
	}

	@Operation(summary = "Call the next waiting customer for a counter",
			description = "Delegates to the atomic SKIP LOCKED claim: concurrent "
					+ "callers never share a row — the loser takes the next "
					+ "eligible entry. Ranking is base priority plus capped aging "
					+ "at now, FIFO inside each level. An empty claim set yields "
					+ "409 (documented, not a server error).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Claimed (now CALLED)",
					content = @Content(schema = @Schema(implementation = QueueEntryResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Outside operational scope"),
			@ApiResponse(responseCode = "404", description = "Unknown counter or foreign organization"),
			@ApiResponse(responseCode = "409", description = "No waiting entries / counter cannot serve")})
	@PostMapping
	public ResponseEntity<QueueEntryResponse> callNext(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Counter id", required = true) @PathVariable UUID counterId) {
		return ResponseEntity.ok(queues.callNext(principal, counterId));
	}
}
