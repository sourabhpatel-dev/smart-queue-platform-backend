package com.sourabh.smartqueue.queue.api;

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
import com.sourabh.smartqueue.queue.application.QueueService;

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
 * Queue state boundary (see ADR-027).
 *
 * <p>The operational snapshot (live entries in claim rank order with
 * positions and waits) plus the paged record listing (stored-field
 * sorting — rank order is deliberately not a page sort; the snapshot
 * carries it instead). Both read PostgreSQL on every call, never Redis.
 * Operational visibility: managers/admins plus assigned staff —
 * customers use their own entry reads and the realtime display. Thin by
 * rule.
 */
@RestController
@RequestMapping("/api/v1/queues/{queueId}")
@Tag(name = "Queues", description = "Walk-in queue operations")
@SecurityRequirement(name = "bearerAuth")
public class QueueController {

	private final QueueService queues;

	public QueueController(QueueService queues) {
		this.queues = queues;
	}

	@Operation(summary = "Read a queue's live snapshot",
			description = "Waiting entries in claim rank order (the same order "
					+ "call-next serves), then called/serving — each with token, "
					+ "position, and estimated wait. Rebuilt from PostgreSQL on "
					+ "every read; never served from Redis.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The snapshot",
					content = @Content(schema = @Schema(implementation = QueueDetailResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Outside operational scope"),
			@ApiResponse(responseCode = "404", description = "Unknown queue or foreign organization")})
	@GetMapping
	public ResponseEntity<QueueDetailResponse> getQueue(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Queue id", required = true) @PathVariable UUID queueId) {
		return ResponseEntity.ok(queues.getQueueDetail(principal, queueId));
	}

	@Operation(summary = "List a queue's entries",
			description = "Paged record listing including terminal history. "
					+ "Sort allow-list: joinedAt (default), tokenNumber, status, "
					+ "priority, createdAt, updatedAt; ties break by id. Optional "
					+ "status, priority, and customerId facets (customer rows stay "
					+ "in-org checked). Live positions/waits are intentionally "
					+ "absent here — the snapshot and single reads carry them.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The page (empty when none)"),
			@ApiResponse(responseCode = "400", description = "Invalid page/size/sort/filter"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Outside operational scope"),
			@ApiResponse(responseCode = "404", description = "Unknown queue or foreign organization")})
	@Parameters({
			@Parameter(name = "page", description = "Zero-based page index", example = "0"),
			@Parameter(name = "size", description = "Page size, 1-100", example = "20"),
			@Parameter(name = "sort",
					description = "Sort entries as field[,asc|desc]; repeatable",
					example = "joinedAt,asc"),
			@Parameter(name = "status", description = "Optional status filter",
					example = "WAITING"),
			@Parameter(name = "priority", description = "Optional priority filter",
					example = "NORMAL"),
			@Parameter(name = "customerId", description = "Optional customer facet",
					example = "00000000-0000-0000-0000-000000000000")})
	@GetMapping("/entries")
	public ResponseEntity<ApiPage<QueueEntryResponse>> listEntries(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Queue id", required = true) @PathVariable UUID queueId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String priority,
			@RequestParam(required = false) UUID customerId,
			HttpServletRequest request) {
		return ResponseEntity.ok(queues.listEntries(principal, queueId, page, size,
				ApiPageRequest.rawSorts(request), status, priority, customerId));
	}
}
