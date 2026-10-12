package com.sourabh.smartqueue.holiday.api;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A new full-day closure. Duplicate dates for the branch fail with
 * {@code 409}.
 */
public record CreateHolidayRequest(
		@NotNull LocalDate date,
		@NotBlank @Size(max = 150) String name) {
}
