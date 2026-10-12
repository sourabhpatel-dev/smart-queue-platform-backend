package com.sourabh.smartqueue.appointment.api;

import java.time.LocalDate;

import com.sourabh.smartqueue.holiday.Holiday;

/**
 * The full-day closure covering the requested date, when one exists.
 * Minimal shape (date + name) — the management read owns the full
 * row. A non-null holiday always means zero slots.
 */
public record AvailabilityHoliday(
		LocalDate date,
		String name) {

	public static AvailabilityHoliday from(Holiday holiday) {
		return new AvailabilityHoliday(holiday.getDate(), holiday.getName());
	}
}
