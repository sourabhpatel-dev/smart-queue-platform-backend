package com.sourabh.smartqueue.workinghour.api;

import java.time.LocalTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A new operating interval: weekday plus minute-granular bounds
 * ({@code 09:00}, never {@code 09:00:30} — the domain rejects
 * seconds). Overlaps with a sibling interval fail with {@code 409};
 * adjacency ({@code 13:00} end / {@code 13:00} start) is allowed by
 * {@code [start, end)} semantics.
 */
public record CreateWorkingHourRequest(
		@NotBlank String dayOfWeek,
		@NotNull LocalTime startTime,
		@NotNull LocalTime endTime) {
}
