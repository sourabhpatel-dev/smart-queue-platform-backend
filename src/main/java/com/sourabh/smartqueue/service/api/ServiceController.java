package com.sourabh.smartqueue.service.api;

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
import com.sourabh.smartqueue.service.application.ServiceManagementService;

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
 * Service item boundary (see ADR-024).
 *
 * <p>Single-service reads, full-replacement updates (PUT only), and the
 * idempotent activate/deactivate commands. Collection reads and creation
 * live under the branch path in {@link BranchServicesController}, so the
 * owning branch is always explicit. Thin by rule — HTTP binding plus
 * delegation; scope and roles live in the service and
 * {@link com.sourabh.smartqueue.auth.TenantAccessPolicy}.
 */
@RestController
@RequestMapping("/api/v1/services")
@Tag(name = "Services", description = "Branch service management")
@SecurityRequirement(name = "bearerAuth")
public class ServiceController {

	private final ServiceManagementService services;

	public ServiceController(ServiceManagementService services) {
		this.services = services;
	}

	@Operation(summary = "Read a service",
			description = "Unknown-or-foreign ids yield 404 (no enumeration); "
					+ "same-organization rows outside the caller's role yield 403.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The service",
					content = @Content(schema = @Schema(implementation = ServiceResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Insufficient authorization"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@GetMapping("/{serviceId}")
	public ResponseEntity<ServiceResponse> getService(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Service id", required = true) @PathVariable UUID serviceId) {
		return ResponseEntity.ok(services.getService(principal, serviceId));
	}

	@Operation(summary = "Replace a service's mutable fields",
			description = "PUT carries the complete mutable representation (name, "
					+ "description, duration) — no PATCH variant. Ownership and status "
					+ "are immutable here. MANAGER or ADMIN only.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Updated",
					content = @Content(schema = @Schema(implementation = ServiceResponse.class))),
			@ApiResponse(responseCode = "400", description = "Validation failure"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization"),
			@ApiResponse(responseCode = "409", description = "Duplicate service name at the branch")})
	@PutMapping("/{serviceId}")
	public ResponseEntity<ServiceResponse> updateService(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Service id", required = true) @PathVariable UUID serviceId,
			@Valid @RequestBody UpdateServiceRequest request) {
		return ResponseEntity.ok(services.updateService(principal, serviceId, request));
	}

	@Operation(summary = "Activate a service",
			description = "Idempotent — activating an active service succeeds "
					+ "unchanged. MANAGER or ADMIN only; foreign rows yield 404.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Activated",
					content = @Content(schema = @Schema(implementation = ServiceResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@PostMapping("/{serviceId}/activate")
	public ResponseEntity<ServiceResponse> activateService(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Service id", required = true) @PathVariable UUID serviceId) {
		return ResponseEntity.ok(services.activateService(principal, serviceId));
	}

	@Operation(summary = "Deactivate a service",
			description = "Status lifecycle, not deletion: appointments, queue "
					+ "entries, and sessions are never touched. Deactivated services "
					+ "stay visible to list/get. Idempotent. MANAGER or ADMIN only.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Deactivated",
					content = @Content(schema = @Schema(implementation = ServiceResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@PostMapping("/{serviceId}/deactivate")
	public ResponseEntity<ServiceResponse> deactivateService(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Service id", required = true) @PathVariable UUID serviceId) {
		return ResponseEntity.ok(services.deactivateService(principal, serviceId));
	}
}
