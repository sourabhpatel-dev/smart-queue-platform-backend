package com.sourabh.smartqueue.user.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.common.api.ApiPage;
import com.sourabh.smartqueue.common.api.ApiPageRequest;
import com.sourabh.smartqueue.user.application.UserManagementService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * User REST boundary (see ADR-025).
 *
 * <p>The user directory is management data: listing, creation, updates,
 * and lifecycle commands need MANAGER/ADMIN in the caller's own
 * organization; single-record reads additionally allow the subject
 * themself. Email is set once at creation and immutable afterwards;
 * roles replace wholesale on PUT (always organization-scoped); status
 * moves through the explicit commands (deactivation preserves history
 * and fails login generically per Phase 3F). Thin by rule — HTTP binding
 * plus delegation; scope and roles live in the service and
 * {@link com.sourabh.smartqueue.auth.TenantAccessPolicy}.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Organization user management")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

	private final UserManagementService users;

	public UserController(UserManagementService users) {
		this.users = users;
	}

	@Operation(summary = "List the caller's organization users",
			description = "Own organization only, paginated (page 0-based, size 1-100). "
					+ "Sort allow-list: email, displayName, status, createdAt, "
					+ "updatedAt as field[,asc|desc]; ties break by id. Optional "
					+ "status and role filters. MANAGER or ADMIN only — the "
					+ "directory is management data, not a public listing.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The page (empty when none)"),
			@ApiResponse(responseCode = "400", description = "Invalid page/size/sort/filter"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN")})
	@Parameters({
			@Parameter(name = "page", description = "Zero-based page index", example = "0"),
			@Parameter(name = "size", description = "Page size, 1-100", example = "20"),
			@Parameter(name = "sort",
					description = "Sort entries as field[,asc|desc]; repeatable "
							+ "(allow-list: email, displayName, status, createdAt, updatedAt)",
					example = "email,asc"),
			@Parameter(name = "status", description = "Optional status filter",
					example = "ACTIVE"),
			@Parameter(name = "role", description = "Optional role filter",
					example = "STAFF")})
	@GetMapping
	public ResponseEntity<ApiPage<UserResponse>> listUsers(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String role,
			HttpServletRequest request) {
		return ResponseEntity.ok(users.listUsers(principal, page, size,
				ApiPageRequest.rawSorts(request), status, role));
	}

	@Operation(summary = "Read a user",
			description = "The subject themself, or MANAGER/ADMIN. Unknown-or-foreign "
					+ "ids yield 404; same-organization rows outside the caller's rule "
					+ "yield 403. Never carries credentials.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The user",
					content = @Content(schema = @Schema(implementation = UserResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Insufficient authorization"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@GetMapping("/{userId}")
	public ResponseEntity<UserResponse> getUser(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "User id", required = true) @PathVariable UUID userId) {
		return ResponseEntity.ok(users.getUser(principal, userId));
	}

	@Operation(summary = "Create a user in the caller's organization",
			description = "MANAGER or ADMIN only. The organization comes from the "
					+ "principal — bodies carry no tenant ids. Roles default to "
					+ "CUSTOMER and stay organization-scoped (granting ADMIN creates "
					+ "an organization admin, never a platform one). An optional "
					+ "plaintext password provisions the credential immediately "
					+ "(BCrypt-hashed, never stored or returned); omitting it leaves "
					+ "a credential-less user that fails login generically. Duplicate "
					+ "emails yield 409 (global uniqueness, constraint-arbitrated).")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Created",
					content = @Content(schema = @Schema(implementation = UserResponse.class))),
			@ApiResponse(responseCode = "400", description = "Validation failure"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "409", description = "Email already in use")})
	@PostMapping
	public ResponseEntity<UserResponse> createUser(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Valid @RequestBody CreateUserRequest request) {
		UserResponse created = users.createUser(principal, request);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{userId}")
				.buildAndExpand(created.id())
				.toUri();
		return ResponseEntity.created(location).body(created);
	}

	@Operation(summary = "Replace a user's mutable profile",
			description = "PUT carries the complete mutable representation "
					+ "(display name + full role set) — no PATCH variant. Email, "
					+ "organization, credentials, and status are immutable here; "
					+ "there is no password-change workflow in this phase and no "
					+ "self-service editing. MANAGER or ADMIN only.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Updated",
					content = @Content(schema = @Schema(implementation = UserResponse.class))),
			@ApiResponse(responseCode = "400", description = "Validation failure"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@PutMapping("/{userId}")
	public ResponseEntity<UserResponse> updateUser(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "User id", required = true) @PathVariable UUID userId,
			@Valid @RequestBody UpdateUserRequest request) {
		return ResponseEntity.ok(users.updateUser(principal, userId, request));
	}

	@Operation(summary = "Activate a user",
			description = "Idempotent. Reactivation restores login when a valid "
					+ "credential exists. MANAGER or ADMIN only; foreign rows yield 404.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Activated",
					content = @Content(schema = @Schema(implementation = UserResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@PostMapping("/{userId}/activate")
	public ResponseEntity<UserResponse> activateUser(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "User id", required = true) @PathVariable UUID userId) {
		return ResponseEntity.ok(users.activateUser(principal, userId));
	}

	@Operation(summary = "Deactivate a user",
			description = "Status lifecycle, not deletion: appointments, queue "
					+ "history, assignments, and audit rows stay intact. Login "
					+ "fails generically from then on (Phase 3F rule, preserved). "
					+ "Idempotent. MANAGER or ADMIN only.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Deactivated",
					content = @Content(schema = @Schema(implementation = UserResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "403", description = "Requires MANAGER or ADMIN"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign organization")})
	@PostMapping("/{userId}/deactivate")
	public ResponseEntity<UserResponse> deactivateUser(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "User id", required = true) @PathVariable UUID userId) {
		return ResponseEntity.ok(users.deactivateUser(principal, userId));
	}
}
