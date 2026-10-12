package com.sourabh.smartqueue.user.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * User update payload (see ADR-025: PUT carries the complete mutable
 * representation — no PATCH variant).
 *
 * <p>Display name plus the full role set (replacing, possibly empty) —
 * required every time. Email is identity and immutable after creation
 * (it keys login and stays globally unique); organization, credentials,
 * status, and audit fields are immutable here. No password field: there
 * is no password-change/reset workflow in this phase by explicit
 * decision. Self-service profile editing is likewise deferred — updates
 * require MANAGER/ADMIN, since roles and profile share this payload.
 */
public record UpdateUserRequest(
		@NotBlank @Size(max = 150) String displayName,
		@NotNull List<String> roles) {
}
