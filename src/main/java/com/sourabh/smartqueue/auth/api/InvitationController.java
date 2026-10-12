package com.sourabh.smartqueue.auth.api;

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
import com.sourabh.smartqueue.auth.application.InvitationService;
import com.sourabh.smartqueue.common.api.ApiPage;
import com.sourabh.smartqueue.common.api.ApiPageRequest;
import com.sourabh.smartqueue.user.RoleType;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

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
 * Employee invitation boundary (see ADR-037).
 *
 * <p>Management routes (invite, list, revoke, resend) require
 * MANAGER/ADMIN in the caller's own organization; acceptance is
 * public and token-bound. Thin by rule — HTTP binding plus
 * delegation only.
 */
@RestController
@RequestMapping("/api/v1/invitations")
@Tag(name = "Invitations", description = "Employee invitation lifecycle")
@SecurityRequirement(name = "bearerAuth")
public class InvitationController {

	private final InvitationService invitations;

	public InvitationController(InvitationService invitations) {
		this.invitations = invitations;
	}

	@Operation(summary = "Invite an employee",
			description = "MANAGER/ADMIN in their own organization. ADMINs may invite MANAGERs "
					+ "and STAFF; MANAGERs may invite STAFF only. STAFF invitations require a branch "
					+ "in the organization. Already-registered emails fail with 409.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Invited; email on its way",
					content = @Content(schema = @Schema(implementation = InvitationResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid request or role rule"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Not management"),
			@ApiResponse(responseCode = "404", description = "Unknown or foreign branch"),
			@ApiResponse(responseCode = "409", description = "Email already registered")})
	@PostMapping
	public ResponseEntity<InvitationResponse> invite(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Valid @RequestBody InviteRequest request) {
		return ResponseEntity.ok(invitations.invite(principal,
				request.email(), RoleType.valueOf(request.role()), request.branchId()));
	}

	@Operation(summary = "List invitations",
			description = "Own organization only, newest first. Paginated (page 0-based, "
					+ "size 1–100); sort allow-list: createdAt (default, desc), email, role.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The page"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Not management")})
	@Parameters({
			@Parameter(name = "page", description = "Zero-based page index", example = "0"),
			@Parameter(name = "size", description = "Page size, 1–100", example = "20"),
			@Parameter(name = "sort", description = "Sort entries as field[,asc|desc]; repeatable",
					example = "createdAt,desc")})
	@GetMapping
	public ResponseEntity<ApiPage<InvitationResponse>> list(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			HttpServletRequest request) {
		return ResponseEntity.ok(invitations.list(principal, page, size,
				ApiPageRequest.rawSorts(request)));
	}

	@Operation(summary = "Revoke an invitation",
			description = "Idempotent for already-revoked rows; accepted invitations cannot "
					+ "be revoked (400). Foreign ids are scope-safe 404s.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Revoked"),
			@ApiResponse(responseCode = "400", description = "Already accepted"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Not management"),
			@ApiResponse(responseCode = "404", description = "Unknown or foreign invitation")})
	@PostMapping("/{invitationId}/revoke")
	public ResponseEntity<Void> revoke(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Invitation id", required = true)
			@PathVariable UUID invitationId) {
		invitations.revoke(principal, invitationId);
		return ResponseEntity.ok().build();
	}

	@Operation(summary = "Resend an invitation",
			description = "Revokes live rows for the recipient and issues a fresh token "
					+ "with a fresh expiry. Accepted invitations cannot be resent (400).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Reissued; email on its way"),
			@ApiResponse(responseCode = "400", description = "Already accepted"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Not management"),
			@ApiResponse(responseCode = "404", description = "Unknown or foreign invitation")})
	@PostMapping("/{invitationId}/resend")
	public ResponseEntity<InvitationResponse> resend(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Invitation id", required = true)
			@PathVariable UUID invitationId) {
		return ResponseEntity.ok(invitations.resend(principal, invitationId));
	}

	@Operation(summary = "Accept an invitation",
			description = "Public. The email must match the invitation; organization, role, "
					+ "and branch come from the invitation, never the request. Creates the "
					+ "account, credential, and staff assignment atomically. Unknown, expired, "
					+ "reused, and mismatched tokens share one generic 400. No session is "
					+ "returned — sign in explicitly next.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Accepted",
					content = @Content(schema = @Schema(implementation = InvitationAcceptanceResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid or expired invitation, weak password"),
			@ApiResponse(responseCode = "409", description = "Email already registered"),
			@ApiResponse(responseCode = "429", description = "Too many attempts")})
	@PostMapping("/accept")
	public ResponseEntity<InvitationAcceptanceResponse> accept(
			@Valid @RequestBody AcceptInvitationRequest request,
			HttpServletRequest httpRequest) {
		InvitationService.Acceptance result = invitations.accept(request.token(),
				request.email(), request.displayName(), request.password(),
				httpRequest.getRemoteAddr());
		return ResponseEntity.ok(new InvitationAcceptanceResponse(
				result.userId(), result.organizationId()));
	}
}
