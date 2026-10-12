package com.sourabh.smartqueue.appointment.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bookable appointment starts for one branch, service, and
 * branch-local date (see ADR-039).
 *
 * <p>Advisory only: the slots reflect committed state at read time —
 * a concurrent booking can take one before the caller books, and the
 * booking endpoint re-arbitrates authoritatively. Only available
 * starts are listed (never reasons for individual gaps, never other
 * customers' appointments). Explicit manual mapping, no framework.
 */
public record AvailabilityResponse(
		UUID branchId,
		UUID serviceId,
		LocalDate date,
		String timezone,
		int serviceDurationMinutes,
		AvailabilityHoliday holiday,
		List<AvailabilityWorkingInterval> workingIntervals,
		List<AvailabilitySlot> slots) {
}
