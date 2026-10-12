package com.sourabh.smartqueue.counter.api;

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
import com.sourabh.smartqueue.counter.application.CounterManagementService;

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
 * Branch-scoped counter collection boundary (see ADR-024).
 *
 * <p>Listing and creation nest under the owning branch so ownership is
 * visible in the path and scoped before any row is touched. Thin by
 * rule — HTTP binding plus delegation only.
 */
@RestController
@RequestMapping("/api/v1/branches/{branchId}/counters")
@Tag(name = "Counters", description = "Branch counter management")
@SecurityRequirement(name = "bearerAuth")
public class BranchCountersController {

	private final CounterManagementService counters;

	public BranchCountersController(CounterManagementService counters) {
		this.counters = counters;
	}

	@Operation(summary = "List a branch's counters",
			description = "Own organization only, paginated (page 0-based, size 1-100). "
					+ "Sort allow-list: name, status, createdAt, updatedAt as "
					+ "field[,asc|desc]; ties break by id. Optional status filter "
					+ "(ACTIVE/INACTIVE/MAINTENANCE). Inactive counters are included "
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
							+ "(allow-list: name, status, createdAt, updatedAt)",
					example = "name,asc"),
			@Parameter(name = "status", description = "Optional status filter",
					example = "ACTIVE")})
	@GetMapping
	public ResponseEntity<ApiPage<CounterResponse>> listCounters(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id within the caller's organization",
					required = true) @PathVariable UUID branchId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String status,
			HttpServletRequest request) {
		return ResponseEntity.ok(counters.listCounters(principal, branchId, page, size,
				ApiPageRequest.rawSorts(request), status));
	}

	@Operation(summary = "Create a counter at the branch",
			description = "MANAGER or ADMIN only. The branch comes from the path "
					+ "(itself scoped to the principal) — request bodies carry no "
					+ "tenant ids. Counters are born ACTIVE; duplicate names at the "
					+ "branch yield 409 (same name elsewhere stays legal).")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Created"),
			@ApiResponse(responseCode = "400", description = "Validation failure"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "404", description = "Unknown branch or foreign organization"),
			@ApiResponse(responseCode = "409", description = "Duplicate counter name at the branch")})
	@PostMapping
	public ResponseEntity<CounterResponse> createCounter(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Branch id within the caller's organization",
					required = true) @PathVariable UUID branchId,
			@Valid @RequestBody CreateCounterRequest request) {
		CounterResponse created = counters.createCounter(principal, branchId, request);
		URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
				.path("/api/v1/counters/{counterId}")
				.buildAndExpand(created.id())
				.toUri();
		return ResponseEntity.created(location).body(created);
	}
}
