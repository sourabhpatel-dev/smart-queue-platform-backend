package com.sourabh.smartqueue.workinghour.api;

import java.time.LocalTime;
import java.util.UUID;

import com.sourabh.smartqueue.workinghour.WorkingHour;

/**
 * One operating interval of a branch on a weekday. Intervals are
 * immutable — corrections are delete + recreate, so no update DTO
 * exists. Explicit manual mapping, no framework.
 */
public record WorkingHourResponse(
		UUID id,
		UUID branchId,
		String dayOfWeek,
		LocalTime startTime,
		LocalTime endTime) {

	public static WorkingHourResponse from(WorkingHour workingHour) {
		return new WorkingHourResponse(
				workingHour.getId(),
				workingHour.getBranch().getId(),
				workingHour.getDayOfWeek().name(),
				workingHour.getStartTime(),
				workingHour.getEndTime());
	}
}
