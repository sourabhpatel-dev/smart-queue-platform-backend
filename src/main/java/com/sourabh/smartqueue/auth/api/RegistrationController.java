package com.sourabh.smartqueue.auth.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sourabh.smartqueue.auth.application.RegistrationService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Public account onboarding (see ADR-040 and ADR-037).
 *
 * <p>All routes here are unauthenticated by design (you cannot
 * require a login to create an account) and rate-limited by client
 * address. Thin by rule — HTTP binding plus delegation only.
 * Duplicate emails fail honestly with {@code 409} (registration
 * must say "already registered" to be usable); every other
 * enumeration-sensitive flow answers generically.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Registration", description = "Public customer/business registration and email verification")
public class RegistrationController {

	private final RegistrationService registrations;

	public RegistrationController(RegistrationService registrations) {
		this.registrations = registrations;
	}

	@Operation(summary = "Register a customer",
			description = "Public. Creates a platform-level CUSTOMER account with no "
					+ "organization (ADR-040) and sends a verification email. Duplicate emails fail with 409.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Registered; verification pending",
					content = @Content(schema = @Schema(implementation = RegistrationResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid request or weak password"),
			@ApiResponse(responseCode = "409", description = "Email already registered"),
			@ApiResponse(responseCode = "429", description = "Too many attempts")})
	@PostMapping("/register")
	public ResponseEntity<RegistrationResponse> registerCustomer(
			@Valid @RequestBody RegisterCustomerRequest request,
			HttpServletRequest httpRequest) {
		RegistrationService.CustomerRegistration result = registrations.registerCustomer(
				request.displayName(), request.email(),
				request.password(), httpRequest.getRemoteAddr());
		return ResponseEntity.ok(new RegistrationResponse(
				result.userId(), result.organizationId(), null, result.email(), true));
	}

	@Operation(summary = "Submit a business application",
			description = "Public. Creates a PENDING_APPROVAL organization, its initial branch, "
					+ "and the owner account (INACTIVE admin membership) atomically, then sends a "
					+ "verification email. Approval by platform review is required before the "
					+ "organization becomes usable. Duplicate owner emails fail with 409.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Application submitted; verification pending",
					content = @Content(schema = @Schema(implementation = RegistrationResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid request, weak password, or bad timezone"),
			@ApiResponse(responseCode = "409", description = "Email already registered"),
			@ApiResponse(responseCode = "429", description = "Too many attempts")})
	@PostMapping("/register/business")
	public ResponseEntity<RegistrationResponse> registerBusiness(
			@Valid @RequestBody RegisterBusinessRequest request,
			HttpServletRequest httpRequest) {
		RegistrationService.BusinessRegistration result = registrations.registerBusiness(
				request.organizationName(), request.ownerName(), request.ownerEmail(),
				request.password(), request.branchName(), request.city(), request.country(),
				request.timeZone(), httpRequest.getRemoteAddr());
		return ResponseEntity.ok(new RegistrationResponse(
				result.userId(), result.organizationId(), result.branchId(),
				request.ownerEmail().trim().toLowerCase(), true));
	}

	@Operation(summary = "Verify an email address",
			description = "Public. Consumes the single-use verification token. Unknown, "
					+ "expired, and reused tokens share one generic 400.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Verified"),
			@ApiResponse(responseCode = "400", description = "Invalid or expired link"),
			@ApiResponse(responseCode = "429", description = "Too many attempts")})
	@PostMapping("/verify-email")
	public ResponseEntity<GenericMessageResponse> verifyEmail(
			@Valid @RequestBody VerifyEmailRequest request,
			HttpServletRequest httpRequest) {
		registrations.verifyEmail(request.token(), httpRequest.getRemoteAddr());
		return ResponseEntity.ok(new GenericMessageResponse("Email verified — you can now sign in."));
	}

	@Operation(summary = "Resend the verification email",
			description = "Public. Always answers generically (no account enumeration): "
					+ "unknown emails, verified accounts, and throttled repeats look identical.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Generic acknowledgement"),
			@ApiResponse(responseCode = "429", description = "Too many attempts")})
	@PostMapping("/resend-verification")
	public ResponseEntity<GenericMessageResponse> resendVerification(
			@Valid @RequestBody ResendVerificationRequest request,
			HttpServletRequest httpRequest) {
		registrations.resendVerification(request.email(), httpRequest.getRemoteAddr());
		return ResponseEntity.ok(GenericMessageResponse.verificationResent());
	}
}
