package com.sourabh.smartqueue.queue.api;

import java.net.URI;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

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
 * Branch-scoped queue collection boundary (see ADR-027).
 *
 * <p>Queue listing (one row per service — a plain array, no pagination)
 * and walk-in joining nest under the owning branch. There is deliberately
 * no queue-creation route: queues are provisioned by service creation in
 * the same transaction (see ADR-038), so no creation flow exists to
 * expose. Thin by rule.
 */
@RestController
@RequestMapping("/api/v1/branches/{branchId}/queues")
@Tag(name = "Queues", description = "Walk-in queue operations")
@SecurityRequirement(name = "bearerAuth")
public class BranchQueuesController {

	private final QueueService queues;

	public BranchQueuesController(QueueService queues) {
		this.queues = queues;
	}

	@Operation(summary = "List a branch's queues",
			description = "One row per service, ordered by service name. "
					+ "Readable by every role in the organization (display data, "
					+ "no personal rows). Foreign branches yield 404.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The branch queues"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Insufficient branch authorization"),
			@ApiResponse(responseCode = "404", description = "Unknown branch or foreign organization")})
	@GetMapping
	public ResponseEntity<List<QueueResponse>> listQueues(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id within the caller's organization",
					required = true) @PathVariable UUID branchId) {
		return ResponseEntity.ok(queues.listQueues(principal, branchId));
	}

	@Operation(summary = "Join a queue as a walk-in",
			description = "Every role joins for itself — the customer is always "
					+ "the principal, no delegated joining exists. Priority "
					+ "defaults to NORMAL; requesting PRIORITY/EMERGENCY needs "
					+ "operational authority at the branch (triage is a staff "
					+ "function). The token, date, position, and wait are "
					+ "server-derived. Duplicate active entries yield 409.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Joined",
					content = @Content(schema = @Schema(implementation = QueueEntryResponse.class))),
			@ApiResponse(responseCode = "400", description = "Validation failure / inactive branch, service, or customer"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Non-operational priority request"),
			@ApiResponse(responseCode = "404", description = "Unknown branch/queue or foreign organization"),
			@ApiResponse(responseCode = "409", description = "Already holds an active entry / queue inactive"),
			@ApiResponse(responseCode = "422", description = "Queue belongs to another branch")})
	@PostMapping("/{queueId}/entries")
	public ResponseEntity<QueueEntryResponse> joinQueue(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id within the caller's organization",
					required = true) @PathVariable UUID branchId,
			@Parameter(description = "Queue id", required = true) @PathVariable UUID queueId,
			@RequestBody(required = false) JoinQueueRequest request) {
		QueueEntryResponse joined = queues.join(principal, branchId, queueId, request);
		URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
				.path("/api/v1/queue-entries/{entryId}")
				.buildAndExpand(joined.id())
				.toUri();
		return ResponseEntity.created(location).body(joined);
	}
}
