package com.sourabh.smartqueue.counter.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.counter.application.CounterCapabilityService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Capability boundary: which services a counter serves (see ADR-024).
 *
 * <p>The association is a small bounded set per counter, so listing
 * returns a plain array (deterministically ordered by service name) —
 * not a paginated envelope. Adding is constraint-arbitrated
 * ({@code 409} on duplicates); removal deletes only the association row
 * (missing → {@code 404}, never the service or counter). Cross-branch
 * links are rejected with {@code 422}; the composite foreign keys stay
 * the backstop. Thin by rule.
 */
@RestController
@RequestMapping("/api/v1/counters/{counterId}/capabilities")
@Tag(name = "Capabilities", description = "Services a counter can serve")
@SecurityRequirement(name = "bearerAuth")
public class CounterCapabilityController {

	private final CounterCapabilityService capabilities;

	public CounterCapabilityController(CounterCapabilityService capabilities) {
		this.capabilities = capabilities;
	}

	@Operation(summary = "List the services a counter serves",
			description = "Bounded plain array ordered by service name. "
					+ "Unknown-or-foreign counters yield 404; same-organization "
					+ "counters outside the caller's role yield 403.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The capabilities",
					content = @Content(array = @ArraySchema(
							schema = @Schema(implementation = CapabilityResponse.class)))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Insufficient authorization"),
			@ApiResponse(responseCode = "404", description = "Unknown counter or foreign organization")})
	@GetMapping
	public ResponseEntity<List<CapabilityResponse>> listCapabilities(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Counter id", required = true) @PathVariable UUID counterId) {
		return ResponseEntity.ok(capabilities.listCapabilities(principal, counterId));
	}

	@Operation(summary = "Let the counter serve a service",
			description = "MANAGER or ADMIN only. The service must live at the "
					+ "counter's own branch: same-organization other-branch services "
					+ "yield 422, foreign-organization services yield 404. Re-adding "
					+ "an existing capability yields 409.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Capability added",
					content = @Content(schema = @Schema(implementation = CapabilityResponse.class))),
			@ApiResponse(responseCode = "400", description = "Validation failure"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "404", description = "Unknown counter/service or foreign organization"),
			@ApiResponse(responseCode = "409", description = "Capability already exists"),
			@ApiResponse(responseCode = "422", description = "Service belongs to another branch")})
	@PostMapping
	public ResponseEntity<CapabilityResponse> addCapability(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Counter id", required = true) @PathVariable UUID counterId,
			@Valid @RequestBody AddCapabilityRequest request) {
		CapabilityResponse created =
				capabilities.addCapability(principal, counterId, request.serviceId());
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{serviceId}")
				.buildAndExpand(created.serviceId())
				.toUri();
		return ResponseEntity.created(location).body(created);
	}

	@Operation(summary = "Stop the counter serving a service",
			description = "Removes only the association — never the service, the "
					+ "counter, or any appointment/queue/session state. A missing "
					+ "capability yields 404. MANAGER or ADMIN only.")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Capability removed"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "404", description = "Unknown counter/capability or foreign organization")})
	@DeleteMapping("/{serviceId}")
	public ResponseEntity<Void> removeCapability(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Counter id", required = true) @PathVariable UUID counterId,
			@Parameter(description = "Service id", required = true) @PathVariable UUID serviceId) {
		capabilities.removeCapability(principal, counterId, serviceId);
		return ResponseEntity.noContent().build();
	}
}
