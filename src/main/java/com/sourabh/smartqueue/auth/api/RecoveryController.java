package com.sourabh.smartqueue.auth.api;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.application.PasswordRecoveryService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Password recovery and change (see ADR-037).
 *
 * <p>Recovery is public and enumeration-safe; change requires an
 * authenticated session plus the current secret. Thin by rule —
 * HTTP binding plus delegation only.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Recovery", description = "Password recovery (public) and change (authenticated)")
public class RecoveryController {

	private final PasswordRecoveryService recovery;

	public RecoveryController(PasswordRecoveryService recovery) {
		this.recovery = recovery;
	}

	@Operation(summary = "Request a password reset",
			description = "Public. Always answers generically — unknown emails look "
					+ "identical to known ones.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Generic acknowledgement"),
			@ApiResponse(responseCode = "429", description = "Too many attempts")})
	@PostMapping("/forgot-password")
	public ResponseEntity<GenericMessageResponse> forgotPassword(
			@Valid @RequestBody ForgotPasswordRequest request,
			HttpServletRequest httpRequest) {
		recovery.requestReset(request.email(), httpRequest.getRemoteAddr());
		return ResponseEntity.ok(GenericMessageResponse.resetSent());
	}

	@Operation(summary = "Reset the password",
			description = "Public. Consumes the single-use token with a fresh password; "
					+ "all prior sessions retire immediately. Unknown, expired, and reused "
					+ "tokens share one generic 400.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Reset; sign in with the new password"),
			@ApiResponse(responseCode = "400", description = "Invalid link or weak password"),
			@ApiResponse(responseCode = "429", description = "Too many attempts")})
	@PostMapping("/reset-password")
	public ResponseEntity<GenericMessageResponse> resetPassword(
			@Valid @RequestBody ResetPasswordRequest request,
			HttpServletRequest httpRequest) {
		recovery.resetPassword(request.token(), request.newPassword(),
				httpRequest.getRemoteAddr());
		return ResponseEntity.ok(
				new GenericMessageResponse("Password reset — you can now sign in."));
	}

	@Operation(summary = "Change the password",
			description = "Authenticated. Proves the current secret first; all other "
					+ "sessions retire immediately.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Changed"),
			@ApiResponse(responseCode = "400", description = "Wrong current password or weak replacement"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication")})
	@SecurityRequirement(name = "bearerAuth")
	@PostMapping("/change-password")
	public ResponseEntity<GenericMessageResponse> changePassword(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Valid @RequestBody ChangePasswordRequest request) {
		recovery.changePassword(principal, request.currentPassword(), request.newPassword());
		return ResponseEntity.ok(
				new GenericMessageResponse("Password changed — other sessions were signed out."));
	}
}
