package com.sourabh.smartqueue.holiday.api;

import java.time.LocalDate;
import java.util.UUID;

import com.sourabh.smartqueue.holiday.Holiday;

/**
 * One full-day branch closure. Explicit manual mapping, no framework.
 */
public record HolidayResponse(
		UUID id,
		UUID branchId,
		LocalDate date,
		String name) {

	public static HolidayResponse from(Holiday holiday) {
		return new HolidayResponse(
				holiday.getId(),
				holiday.getBranch().getId(),
				holiday.getDate(),
				holiday.getName());
	}
}
