package com.sourabh.smartqueue.auth.api;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Invite an employee: email, role, and (for staff) branch. Only
 * {@code MANAGER} and {@code STAFF} are invitable — admins are never
 * created through this path.
 */
public record InviteRequest(
		@Email @NotBlank @Size(max = 255) String email,
		@NotNull @Pattern(regexp = "MANAGER|STAFF",
				message = "Role must be MANAGER or STAFF") String role,
		UUID branchId) {
}
