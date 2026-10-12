package com.sourabh.smartqueue.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Minimal authentication API (see ADR-021).
 *
 * <p>Exactly one endpoint: email + password in, bearer token out. This does
 * NOT open the broader REST API — organizations, branches, queues,
 * appointments, and users remain future API phases. The token authenticates
 * subsequent REST calls (via {@code Authorization: Bearer}) and STOMP
 * sessions (via the CONNECT header).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Login — the only public application endpoint")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@Operation(summary = "Login with email + password",
			description = "Public. Exchanges credentials for a bearer token "
					+ "used as Authorization: Bearer <token> (REST) or ?token= (WebSocket). "
					+ "Failures are generic (no account enumeration).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Bearer token issued",
					content = @Content(schema = @Schema(implementation = LoginResponse.class))),
			@ApiResponse(responseCode = "400", description = "Malformed login request"),
			@ApiResponse(responseCode = "401", description = "Invalid email or password")})
	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
		AuthService.LoginResult result = authService.login(request.email(), request.password());
		return ResponseEntity.ok(LoginResponse.bearer(result));
	}
}
