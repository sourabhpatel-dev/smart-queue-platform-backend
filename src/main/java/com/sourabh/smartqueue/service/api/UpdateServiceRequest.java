package com.sourabh.smartqueue.service.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Service update payload (see ADR-024: PUT carries the complete mutable
 * representation — no PATCH variant).
 *
 * <p>Same mutable fields as creation, all required. No ownership fields
 * (a service never moves branches through this API), no {@code status}
 * (lifecycle moves through activate/deactivate only), no audit fields.
 */
public record UpdateServiceRequest(
		@NotBlank @Size(max = 150) String name,
		@Size(max = 1000) String description,
		@NotNull @Min(1) Integer durationMinutes) {
}
