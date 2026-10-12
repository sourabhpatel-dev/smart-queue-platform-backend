package com.sourabh.smartqueue.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Customer self-registration (see ADR-040).
 *
 * <p>A customer is a <strong>platform-level identity</strong>: there is no
 * organization to choose and no membership is created. The organization is
 * chosen later, per action, when booking an appointment or joining a queue
 * (the branch/queue in the request names it). Shape validation only;
 * password strength and duplicates are service rules with their own codes.
 * Raw passwords live only for the registration call: never persisted, never
 * logged, never returned.
 */
public record RegisterCustomerRequest(
		@NotBlank @Size(max = 150) String displayName,
		@Email @NotBlank @Size(max = 255) String email,
		@NotBlank @Size(max = 72) String password) {
}
