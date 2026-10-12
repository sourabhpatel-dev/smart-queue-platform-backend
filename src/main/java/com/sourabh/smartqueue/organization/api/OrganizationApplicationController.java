package com.sourabh.smartqueue.organization.api;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.organization.application.OrganizationApplicationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The applicant's own business-application status (see ADR-041).
 *
 * <p>Authenticated by design (ownership comes from the caller's
 * membership row). Thin by rule — HTTP binding plus delegation.
 * Callers without an application get the scope-safe {@code 404}, so
 * applications are never enumerable here.
 */
@RestController
@RequestMapping("/api/v1/organizations/applications")
@Tag(name = "Organization applications", description = "Business-application status for the applicant")
@SecurityRequirement(name = "bearerAuth")
public class OrganizationApplicationController {

	private final OrganizationApplicationService applications;

	public OrganizationApplicationController(OrganizationApplicationService applications) {
		this.applications = applications;
	}

	@Operation(summary = "Read my business application",
			description = "Returns the caller's own organization application: status, whether "
					+ "their email verification is complete, and any owner-visible rejection "
					+ "reason. Callers with no application get 404.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The application",
					content = @Content(schema = @Schema(implementation = ApplicationStatusResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "404", description = "No business application for this account")})
	@GetMapping("/mine")
	public ResponseEntity<ApplicationStatusResponse> myApplication(
			@AuthenticationPrincipal AuthenticatedPrincipal principal) {
		return ResponseEntity.ok(applications.myApplication(principal));
	}
}
