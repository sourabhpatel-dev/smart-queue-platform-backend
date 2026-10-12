package com.sourabh.smartqueue.counter.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.counter.application.CounterManagementService;

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
 * Counter item boundary (see ADR-024).
 *
 * <p>Single-counter reads, full-replacement updates (PUT only), and the
 * idempotent activate/deactivate commands — deactivation never removes
 * capabilities. Collection reads and creation live under the branch path
 * in {@link BranchCountersController}; served services live behind
 * {@link CounterCapabilityController}. Thin by rule.
 */
@RestController
@RequestMapping("/api/v1/counters")
@Tag(name = "Counters", description = "Branch counter management")
@SecurityRequirement(name = "bearerAuth")
public class CounterController {

	private final CounterManagementService counters;

	public CounterController(CounterManagementService counters) {
		this.counters = counters;
	}

	@Operation(summary = "Read a counter",
			description = "Unknown-or-foreign ids yield 404 (no enumeration); "
					+ "same-organization rows outside the caller's role yield 403. "
					+ "Capabilities are not embedded — see the capability endpoints.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The counter",
					content = @Content(schema = @Schema(implementation = CounterResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Insufficient authorization"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@GetMapping("/{counterId}")
	public ResponseEntity<CounterResponse> getCounter(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Counter id", required = true) @PathVariable UUID counterId) {
		return ResponseEntity.ok(counters.getCounter(principal, counterId));
	}

	@Operation(summary = "Rename a counter",
			description = "PUT carries the complete mutable representation — the "
					+ "name is the only mutable field, and it stays required. "
					+ "Ownership and status are immutable here. MANAGER or ADMIN only.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Updated",
					content = @Content(schema = @Schema(implementation = CounterResponse.class))),
			@ApiResponse(responseCode = "400", description = "Validation failure"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization"),
			@ApiResponse(responseCode = "409", description = "Duplicate counter name at the branch")})
	@PutMapping("/{counterId}")
	public ResponseEntity<CounterResponse> updateCounter(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Counter id", required = true) @PathVariable UUID counterId,
			@Valid @RequestBody UpdateCounterRequest request) {
		return ResponseEntity.ok(counters.updateCounter(principal, counterId, request));
	}

	@Operation(summary = "Activate a counter",
			description = "Idempotent — activating an active counter succeeds "
					+ "unchanged. MANAGER or ADMIN only; foreign rows yield 404.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Activated",
					content = @Content(schema = @Schema(implementation = CounterResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@PostMapping("/{counterId}/activate")
	public ResponseEntity<CounterResponse> activateCounter(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Counter id", required = true) @PathVariable UUID counterId) {
		return ResponseEntity.ok(counters.activateCounter(principal, counterId));
	}

	@Operation(summary = "Deactivate a counter",
			description = "Status lifecycle, not deletion: capabilities are kept, "
					+ "so reactivation restores service. Deactivated counters stay "
					+ "visible to list/get. Idempotent. MANAGER or ADMIN only.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Deactivated",
					content = @Content(schema = @Schema(implementation = CounterResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@PostMapping("/{counterId}/deactivate")
	public ResponseEntity<CounterResponse> deactivateCounter(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Counter id", required = true) @PathVariable UUID counterId) {
		return ResponseEntity.ok(counters.deactivateCounter(principal, counterId));
	}
}
