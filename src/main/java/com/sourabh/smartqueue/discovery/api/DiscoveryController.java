package com.sourabh.smartqueue.discovery.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sourabh.smartqueue.discovery.application.DiscoveryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Public service directory (see the discovery decision notes).
 *
 * <p>Unauthenticated by design: a new customer must find clinics
 * before having an account. Only intentionally public rows are
 * exposed — active organizations (id + name), active branches of
 * one organization (id, name, city, country, timezone), active
 * services of one branch (id, name, description, duration). No
 * staff, no customers, no queue state, no internal detail. Tenant
 * data beyond the directory stays behind authentication.
 */
@RestController
@RequestMapping("/api/v1/discovery")
@Tag(name = "Discovery", description = "Public clinic/service directory for registration and discovery")
public class DiscoveryController {

	private final DiscoveryService discovery;

	public DiscoveryController(DiscoveryService discovery) {
		this.discovery = discovery;
	}

	@Operation(summary = "List active organizations",
			description = "Public. Id + name only — the registration org picker.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Active organizations")})
	@GetMapping("/organizations")
	public ResponseEntity<List<OrganizationSummary>> organizations() {
		return ResponseEntity.ok(discovery.organizations());
	}

	@Operation(summary = "List active branches",
			description = "Public. One organization's active branches with the "
					+ "timezone booking needs. Unknown organizations are 404.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Active branches"),
			@ApiResponse(responseCode = "400", description = "Missing organization id"),
			@ApiResponse(responseCode = "404", description = "Unknown organization")})
	@GetMapping("/branches")
	public ResponseEntity<List<BranchSummary>> branches(
			@Parameter(description = "Organization id", required = true)
			@RequestParam UUID organizationId) {
		return ResponseEntity.ok(discovery.branches(organizationId));
	}

	@Operation(summary = "List active services",
			description = "Public. One branch's active services with durations. "
					+ "Unknown branches are 404.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Active services"),
			@ApiResponse(responseCode = "400", description = "Missing branch id"),
			@ApiResponse(responseCode = "404", description = "Unknown branch")})
	@GetMapping("/services")
	public ResponseEntity<List<ServiceSummary>> services(
			@Parameter(description = "Branch id", required = true)
			@RequestParam UUID branchId) {
		return ResponseEntity.ok(discovery.services(branchId));
	}
}
