package com.sourabh.smartqueue.service.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.common.api.ApiPage;
import com.sourabh.smartqueue.common.api.ApiPageRequest;
import com.sourabh.smartqueue.service.application.ServiceManagementService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Branch-scoped service collection boundary (see ADR-024).
 *
 * <p>Listing and creation nest under the owning branch so ownership is
 * visible in the path and scoped before any row is touched. Thin by
 * rule — HTTP binding plus delegation only.
 */
@RestController
@RequestMapping("/api/v1/branches/{branchId}/services")
@Tag(name = "Services", description = "Branch service management")
@SecurityRequirement(name = "bearerAuth")
public class BranchServicesController {

	private final ServiceManagementService services;

	public BranchServicesController(ServiceManagementService services) {
		this.services = services;
	}

	@Operation(summary = "List a branch's services",
			description = "Own organization only, paginated (page 0-based, size 1-100). "
					+ "Sort allow-list: name, status, durationMinutes, createdAt, "
					+ "updatedAt as field[,asc|desc]; ties break by id. Optional "
					+ "status filter (ACTIVE/INACTIVE). Inactive services are included "
					+ "unless filtered. Readable by every role in the organization.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The page (empty when none)"),
			@ApiResponse(responseCode = "400", description = "Invalid page/size/sort/status"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Insufficient branch authorization"),
			@ApiResponse(responseCode = "404", description = "Unknown branch or foreign organization")})
	@Parameters({
			@Parameter(name = "page", description = "Zero-based page index", example = "0"),
			@Parameter(name = "size", description = "Page size, 1-100", example = "20"),
			@Parameter(name = "sort",
					description = "Sort entries as field[,asc|desc]; repeatable "
							+ "(allow-list: name, status, durationMinutes, createdAt, updatedAt)",
					example = "name,asc"),
			@Parameter(name = "status", description = "Optional status filter",
					example = "ACTIVE")})
	@GetMapping
	public ResponseEntity<ApiPage<ServiceResponse>> listServices(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id within the caller's organization",
					required = true) @PathVariable UUID branchId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String status,
			HttpServletRequest request) {
		return ResponseEntity.ok(services.listServices(principal, branchId, page, size,
				ApiPageRequest.rawSorts(request), status));
	}

	@Operation(summary = "Create a service at the branch",
			description = "MANAGER or ADMIN only. The branch comes from the path "
					+ "(itself scoped to the principal) — request bodies carry no "
					+ "tenant ids. Services are born ACTIVE; duplicate names at the "
					+ "branch yield 409 (same name elsewhere stays legal).")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Created"),
			@ApiResponse(responseCode = "400", description = "Validation failure"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "404", description = "Unknown branch or foreign organization"),
			@ApiResponse(responseCode = "409", description = "Duplicate service name at the branch")})
	@PostMapping
	public ResponseEntity<ServiceResponse> createService(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id within the caller's organization",
					required = true) @PathVariable UUID branchId,
			@Valid @RequestBody CreateServiceRequest request) {
		ServiceResponse created = services.createService(principal, branchId, request);
		URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
				.path("/api/v1/services/{serviceId}")
				.buildAndExpand(created.id())
				.toUri();
		return ResponseEntity.created(location).body(created);
	}
}
