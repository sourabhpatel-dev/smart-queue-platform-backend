package com.sourabh.smartqueue.counter.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Counter creation payload (see ADR-024).
 *
 * <p>Client-controlled fields only: no {@code id}, no ownership overrides
 * (ownership comes from the path branch, itself scoped to the principal),
 * no {@code status} (born {@code ACTIVE}), no audit fields. Served
 * services are managed through the capability endpoints, never here.
 */
public record CreateCounterRequest(
		@NotBlank @Size(max = 100) String name) {
}
