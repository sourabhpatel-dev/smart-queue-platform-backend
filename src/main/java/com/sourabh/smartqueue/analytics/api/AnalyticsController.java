package com.sourabh.smartqueue.analytics.api;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sourabh.smartqueue.analytics.application.AnalyticsService;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Operational analytics boundary (see ADR-035).
 *
 * <p>Read-only management reporting over committed PostgreSQL state.
 * Thin by rule — HTTP binding plus delegation only. Managers and
 * admins within their own organization; staff and customers are
 * denied by the service's management-role gate (backend-enforced,
 * never frontend-only).
 */
@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics", description = "Operational reporting for managers and admins")
@SecurityRequirement(name = "bearerAuth")
public class AnalyticsController {

	private final AnalyticsService analytics;

	public AnalyticsController(AnalyticsService analytics) {
		this.analytics = analytics;
	}

	@Operation(summary = "Operational overview",
			description = "One coherent report for a local date-time window. "
					+ "from/to are local date-times (ISO-8601, e.g. "
					+ "2026-10-01T00:00:00) interpreted in the effective "
					+ "timezone with [from, to) semantics (from inclusive, "
					+ "to exclusive); the window must satisfy from < to and "
					+ "stay within the configured maximum range (default 366 "
					+ "days — overlong ranges are rejected, never truncated). "
					+ "timezone is an IANA ZoneId; it defaults to the "
					+ "selected branch's zone and is required for "
					+ "organization-wide reports. branchId/serviceId scope "
					+ "the report (scope-safe 404 when foreign; 422 when the "
					+ "service is not in the branch). Appointments anchor on "
					+ "startAt, queue entries on joinedAt, sessions on "
					+ "startedAt. Counts are 0 when nothing happened; "
					+ "averages are null when there were no observations. "
					+ "Statuses are reported exactly as stored — no "
					+ "historical transitions are claimed.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The report (zero-filled when no activity)",
					content = @Content(schema = @Schema(implementation = AnalyticsOverviewResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid range, timezone, or date-time format"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Staff or customer (management only)"),
			@ApiResponse(responseCode = "404", description = "Unknown or foreign branch/service id"),
			@ApiResponse(responseCode = "422", description = "Service does not belong to the requested branch")})
	@Parameters({
			@Parameter(name = "from", description = "Window start, local date-time in the effective timezone",
					example = "2026-10-01T00:00:00", required = true),
			@Parameter(name = "to", description = "Window end (exclusive), local date-time",
					example = "2026-10-08T00:00:00", required = true),
			@Parameter(name = "timezone", description = "IANA ZoneId; defaults to the branch zone when branchId is set",
					example = "Asia/Kolkata"),
			@Parameter(name = "branchId", description = "Optional branch scope",
					example = "00000000-0000-0000-0000-000000000000"),
			@Parameter(name = "serviceId", description = "Optional service scope (requires no branch, but must belong to it when combined)",
					example = "00000000-0000-0000-0000-000000000000")})
	@GetMapping("/overview")
	public ResponseEntity<AnalyticsOverviewResponse> overview(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@RequestParam LocalDateTime from,
			@RequestParam LocalDateTime to,
			@RequestParam(required = false) String timezone,
			@RequestParam(required = false) UUID branchId,
			@RequestParam(required = false) UUID serviceId) {
		return ResponseEntity.ok(
				analytics.overview(principal, from, to, timezone, branchId, serviceId));
	}
}
