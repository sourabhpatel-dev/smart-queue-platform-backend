package com.sourabh.smartqueue.service.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Service creation payload (see ADR-024).
 *
 * <p>Client-controlled fields only: no {@code id}, no {@code organizationId}
 * or {@code branchId} (ownership comes from the path branch, itself scoped
 * to the principal), no {@code status} (born {@code ACTIVE}), no audit
 * fields. Duration is whole minutes (the entity's storage unit, so no
 * sub-minute edge exists at this boundary); bounds mirror the columns.
 */
public record CreateServiceRequest(
		@NotBlank @Size(max = 150) String name,
		@Size(max = 1000) String description,
		@NotNull @Min(1) Integer durationMinutes) {
}
