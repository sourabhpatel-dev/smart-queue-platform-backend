package com.sourabh.smartqueue.platform.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Rejection for a business application (see ADR-041).
 *
 * <p>The reason is shown to the applicant, so reviewers must write it
 * as applicant-facing feedback — there is no internal-notes field.
 */
public record RejectApplicationRequest(
		@NotBlank @Size(max = 500) String reason) {
}
