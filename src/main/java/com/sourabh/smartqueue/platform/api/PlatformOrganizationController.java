package com.sourabh.smartqueue.platform.api;

import java.util.List;
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

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.platform.application.OrganizationReviewService;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Platform-administrator review of business applications (see ADR-041).
 *
 * <p>Authenticated by design; every call re-authorizes through the
 * platform-admin policy (configuration + live account state), so
 * customers, staff, managers, and organization admins all get the
 * same {@code 403}. Thin by rule — HTTP binding plus delegation.
 */
@RestController
@RequestMapping("/api/v1/platform/organizations")
@Tag(name = "Platform administration", description = "Business-application review for platform administrators")
@SecurityRequirement(name = "bearerAuth")
public class PlatformOrganizationController {

	private final OrganizationReviewService review;

	public PlatformOrganizationController(OrganizationReviewService review) {
		this.review = review;
	}

	@Operation(summary = "List organization applications",
			description = "Platform administrators only. Pending applications by default "
					+ "(oldest first); any lifecycle status may be named explicitly.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Applications"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Not a platform administrator")})
	@GetMapping
	public ResponseEntity<List<PlatformApplicationResponse>> listApplications(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Lifecycle status filter (default PENDING_APPROVAL)")
			@RequestParam(required = false) String status) {
		return ResponseEntity.ok(review.listApplications(principal, status));
	}

	@Operation(summary = "Read one organization application",
			description = "Platform administrators only. Unknown ids are 404.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The application",
					content = @Content(schema = @Schema(implementation = PlatformApplicationResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Not a platform administrator"),
			@ApiResponse(responseCode = "404", description = "Unknown organization")})
	@GetMapping("/{organizationId}")
	public ResponseEntity<PlatformApplicationResponse> getApplication(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Organization id", required = true)
			@PathVariable UUID organizationId) {
		return ResponseEntity.ok(review.getApplication(principal, organizationId));
	}

	@Operation(summary = "Approve an organization application",
			description = "Platform administrators only. Requires the owner's email "
					+ "verification and PENDING_APPROVAL status; activates the organization "
					+ "and the owner's admin membership together. Anything else is 409.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Approved",
					content = @Content(schema = @Schema(implementation = PlatformApplicationResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Not a platform administrator"),
			@ApiResponse(responseCode = "404", description = "Unknown organization"),
			@ApiResponse(responseCode = "409", description = "Not eligible for approval")})
	@PostMapping("/{organizationId}/approve")
	public ResponseEntity<PlatformApplicationResponse> approve(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Organization id", required = true)
			@PathVariable UUID organizationId) {
		return ResponseEntity.ok(review.approve(principal, organizationId));
	}

	@Operation(summary = "Reject an organization application",
			description = "Platform administrators only. Requires PENDING_APPROVAL status; "
					+ "the reason is shown to the applicant. Anything else is 409.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Rejected",
					content = @Content(schema = @Schema(implementation = PlatformApplicationResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid reason"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Not a platform administrator"),
			@ApiResponse(responseCode = "404", description = "Unknown organization"),
			@ApiResponse(responseCode = "409", description = "Not eligible for rejection")})
	@PostMapping("/{organizationId}/reject")
	public ResponseEntity<PlatformApplicationResponse> reject(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Organization id", required = true)
			@PathVariable UUID organizationId,
			@Valid @RequestBody RejectApplicationRequest request) {
		return ResponseEntity.ok(review.reject(principal, organizationId, request.reason()));
	}
}
