package com.sourabh.smartqueue.queue.api;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.queue.QueueJoinEstimate;
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
 * Pre-join waiting-time estimate for a queue (see ADR-015).
 *
 * <p>Returns aggregate wait if a new entry were to join right now: how many
 * people are already waiting, the estimated whole-minute wait, how many
 * capable counters are open, and the queue's operational state. The
 * estimator re-uses the same scope and rounding as the per-entry
 * {@link WaitTimeEstimate} — position = last among current WAITING entries.
 */
@RestController
@RequestMapping("/api/v1/branches/{branchId}/queues/{serviceId}/estimate-join")
@Tag(name = "Queue Estimates", description = "Pre-join waiting-time estimates")
@SecurityRequirement(name = "bearerAuth")
public class QueuePreJoinEstimateController {

	private final QueueService queues;

	public QueuePreJoinEstimateController(QueueService queues) {
		this.queues = queues;
	}

	@Operation(summary = "Estimate wait if joining the queue now",
			description = "Aggregate wait for a new entry: waitingCount, "
					+ "estimatedWaitMinutes (whole minutes from now), capableCounterCount, "
					+ "activeSessions, and queueStatus. The estimate uses the same "
					+ "scope and formula as the per-entry estimator — a new entry "
					+ "ranks last among currently WAITING entries in the capability "
					+ "set. Returns {@code 400} if branch/service/queue are not active, "
					+ "and {@code 404} if the branch, service, or queue is unknown "
					+ "or belongs to another organization.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Estimate available",
					content = @Content(schema = @Schema(implementation = QueueJoinEstimate.class))),
			@ApiResponse(responseCode = "400", description = "Validation failure "
					+ "(inactive branch, service, or queue)"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Insufficient branch authorization"),
			@ApiResponse(responseCode = "404", description = "Unknown branch/queue/service "
					+ "or foreign organization")})
	@GetMapping
	public ResponseEntity<QueueJoinEstimate> estimateJoin(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id within the caller's organization",
					required = true) @PathVariable UUID branchId,
			@Parameter(description = "Service id within the branch",
					required = true) @PathVariable UUID serviceId) {
		QueueJoinEstimate estimate = queues.estimateJoin(principal, branchId, serviceId);
		return ResponseEntity.ok(estimate);
	}
}