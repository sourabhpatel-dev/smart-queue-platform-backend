package com.sourabh.smartqueue.organization.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.organization.application.OrganizationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Organization REST boundary (see ADR-022).
 *
 * <p>Deliberately one read: it proves JWT → principal → tenant scope →
 * policy → service → DTO without opening a CRUD surface. Thin by rule —
 * HTTP binding plus delegation; no queries, no business rules, no manual
 * tenant checks (the service owns scope).
 */
@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "Organizations", description = "Tenant organization reads")
@SecurityRequirement(name = "bearerAuth")
public class OrganizationController {

	private final OrganizationService organizations;

	public OrganizationController(OrganizationService organizations) {
		this.organizations = organizations;
	}

	@Operation(summary = "Read the caller's organization",
			description = "Returns the authenticated principal's own organization. "
					+ "Any other id yields 404, so tenants cannot be enumerated.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The organization",
					content = @Content(schema = @Schema(implementation = OrganizationResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@GetMapping("/{organizationId}")
	public ResponseEntity<OrganizationResponse> getOrganization(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Organization id (must be the caller's own)",
					required = true) @PathVariable UUID organizationId) {
		return ResponseEntity.ok(organizations.getOrganization(principal, organizationId));
	}
}
