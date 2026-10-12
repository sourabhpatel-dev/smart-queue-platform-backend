package com.sourabh.smartqueue.auth.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.LoginResponse;
import com.sourabh.smartqueue.auth.oauth.OAuthService;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Social login boundary (see the OAuth decision notes).
 *
 * <p>Public by necessity (you cannot require a session to start
 * one). The frontend never sees provider secrets: it redirects to
 * the provider, receives an authorization code, and hands the code
 * here — this service exchanges, verifies, links-or-provisions, and
 * returns the standard bearer token. Identity failures share the
 * generic login failure.
 */
@RestController
@RequestMapping("/api/v1/auth/oauth")
@Tag(name = "Social login", description = "Google/Apple sign-in (configured providers only)")
public class OAuthController {

	private final OAuthService oauth;

	public OAuthController(OAuthService oauth) {
		this.oauth = oauth;
	}

	@Operation(summary = "List enabled providers",
			description = "Public. Names only (GOOGLE and/or APPLE) — the login UI "
					+ "renders buttons exactly for these, never for anything else.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Enabled providers (possibly empty)")})
	@GetMapping("/providers")
	public ResponseEntity<OAuthProvidersResponse> providers() {
		return ResponseEntity.ok(new OAuthProvidersResponse(oauth.enabledProviders()));
	}

	@Operation(summary = "Complete a social sign-in",
			description = "Public. Exchanges the authorization code server-side, validates "
					+ "the ID token (issuer, audience, signature, expiry), then links a known "
					+ "identity, links a verified-email match, or provisions a CUSTOMER in the "
					+ "given organization. Failures are generic (no enumeration).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Bearer token issued",
					content = @Content(schema = @Schema(implementation = LoginResponse.class))),
			@ApiResponse(responseCode = "400", description = "Malformed request, disabled/unknown provider, or missing organization for new emails"),
			@ApiResponse(responseCode = "401", description = "Identity could not be verified")})
	@PostMapping("/{provider}/callback")
	public ResponseEntity<LoginResponse> callback(
			@Parameter(description = "GOOGLE or APPLE", required = true)
			@PathVariable String provider,
			@Valid @RequestBody OAuthCallbackRequest request) {
		AuthService.LoginResult result = oauth.authenticate(provider,
				request.code(), request.redirectUri(), request.organizationId());
		return ResponseEntity.ok(LoginResponse.bearer(result));
	}
}
