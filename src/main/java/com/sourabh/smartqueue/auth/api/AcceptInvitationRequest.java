package com.sourabh.smartqueue.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Accept an invitation: the token plus the invitee's own details.
 * The email must match the invitation (transfer prevention); the
 * organization, role, and branch come from the invitation, never
 * from this body.
 */
public record AcceptInvitationRequest(
		@NotBlank String token,
		@Email @NotBlank @Size(max = 255) String email,
		@NotBlank @Size(max = 150) String displayName,
		@NotBlank @Size(max = 72) String password) {
}
