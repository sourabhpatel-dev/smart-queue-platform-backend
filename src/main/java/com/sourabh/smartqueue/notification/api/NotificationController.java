package com.sourabh.smartqueue.notification.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.common.api.ApiPage;
import com.sourabh.smartqueue.common.api.ApiPageRequest;
import com.sourabh.smartqueue.notification.application.NotificationService;

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

/**
 * In-app notification REST boundary (see ADR-032).
 *
 * <p>Every operation scopes to the authenticated principal as the
 * recipient — no user id is accepted from the client, so there is no
 * recipient parameter to spoof. Unknown-or-foreign ids yield
 * scope-safe {@code 404}s. Read state moves only through the explicit
 * read commands (no PUT); both are idempotent. Thin by rule — HTTP
 * binding plus delegation.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "In-app notification center")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

	private final NotificationService notifications;

	public NotificationController(NotificationService notifications) {
		this.notifications = notifications;
	}

	@Operation(summary = "List the caller's notifications",
			description = "Recipient-scoped to the authenticated user, newest first "
					+ "by default (page 0-based, size 1-100). Sort allow-list: "
					+ "createdAt, readAt as field[,asc|desc]; ties break by id. "
					+ "Optional unread filter (true = unread only, false = read only).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The page (empty when none)"),
			@ApiResponse(responseCode = "400", description = "Invalid page/size/sort/filter"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication")})
	@Parameters({
			@Parameter(name = "page", description = "Zero-based page index", example = "0"),
			@Parameter(name = "size", description = "Page size, 1-100", example = "20"),
			@Parameter(name = "sort",
					description = "Sort entries as field[,asc|desc]; repeatable "
							+ "(allow-list: createdAt, readAt)",
					example = "createdAt,desc"),
			@Parameter(name = "unread", description = "Optional read-state filter",
					example = "true")})
	@GetMapping
	public ResponseEntity<ApiPage<NotificationResponse>> listNotifications(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String unread,
			HttpServletRequest request) {
		return ResponseEntity.ok(notifications.listNotifications(principal, page, size,
				ApiPageRequest.rawSorts(request), unread));
	}

	@Operation(summary = "Count the caller's unread notifications",
			description = "Database-computed badge value — the frontend never "
					+ "pages through rows to derive it.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The unread count"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication")})
	@GetMapping("/unread-count")
	public ResponseEntity<Map<String, Long>> unreadCount(
			@AuthenticationPrincipal AuthenticatedPrincipal principal) {
		return ResponseEntity.ok(Map.of("unreadCount", notifications.unreadCount(principal)));
	}

	@Operation(summary = "Read one notification",
			description = "Recipient-scoped: unknown-or-foreign ids yield 404.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The notification",
					content = @Content(schema = @Schema(implementation = NotificationResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign recipient")})
	@GetMapping("/{notificationId}")
	public ResponseEntity<NotificationResponse> getNotification(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Notification id", required = true) @PathVariable UUID notificationId) {
		return ResponseEntity.ok(notifications.getNotification(principal, notificationId));
	}

	@Operation(summary = "Mark one notification as read",
			description = "Idempotent: an already-read row keeps its first stamp.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The notification"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
			@ApiResponse(responseCode = "404", description = "Unknown id or foreign recipient")})
	@PostMapping("/{notificationId}/read")
	public ResponseEntity<NotificationResponse> markRead(
			@AuthenticationPrincipal AuthenticatedPrincipal principal,
			@Parameter(description = "Notification id", required = true) @PathVariable UUID notificationId) {
		return ResponseEntity.ok(notifications.markRead(principal, notificationId));
	}

	@Operation(summary = "Mark all of the caller's notifications as read",
			description = "Affects only the authenticated user's rows. Returns "
					+ "how many rows changed (already-read rows are untouched).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Rows marked read"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid authentication")})
	@PostMapping("/read-all")
	public ResponseEntity<Map<String, Integer>> markAllRead(
			@AuthenticationPrincipal AuthenticatedPrincipal principal) {
		return ResponseEntity.ok(Map.of("markedRead", notifications.markAllRead(principal)));
	}
}
