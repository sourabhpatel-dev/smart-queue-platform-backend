package com.sourabh.smartqueue.appointment.api;

import java.time.LocalTime;

import com.sourabh.smartqueue.workinghour.WorkingHour;

/**
 * One configured operating interval of the branch on the requested
 * weekday, echoed verbatim (split intervals stay split). Wall-clock
 * in the branch timezone — display only, never converted.
 */
public record AvailabilityWorkingInterval(
		LocalTime opensAt,
		LocalTime closesAt) {

	public static AvailabilityWorkingInterval from(WorkingHour workingHour) {
		return new AvailabilityWorkingInterval(
				workingHour.getStartTime(),
				workingHour.getEndTime());
	}
}
