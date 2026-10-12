package com.sourabh.smartqueue.branch.application;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.branch.BranchStatus;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.holiday.Holiday;
import com.sourabh.smartqueue.holiday.HolidayRepository;
import com.sourabh.smartqueue.workinghour.WorkingHour;
import com.sourabh.smartqueue.workinghour.WorkingHourRepository;
import com.sourabh.smartqueue.common.api.InvalidRequestException;

import com.sourabh.smartqueue.appointment.api.AvailabilityWorkingInterval;

@Service
public class ScheduleStatusService {

	private final BranchRepository branches;
	private final WorkingHourRepository workingHours;
	private final HolidayRepository holidays;
	private final Clock clock;

	public ScheduleStatusService(BranchRepository branches,
			WorkingHourRepository workingHours,
			HolidayRepository holidays,
			Clock clock) {
		this.branches = branches;
		this.workingHours = workingHours;
		this.holidays = holidays;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ScheduleStatusResponse getScheduleStatus(AuthenticatedPrincipal principal, UUID branchId) {
		Branch branch = branches.findById(branchId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"BRANCH_NOT_FOUND", "Branch not found"));
		if (principal.isOperational()) {
			if (!branch.getOrganization().getId().equals(principal.organizationId())) {
				throw new ResourceNotFoundException(
						"BRANCH_NOT_FOUND", "Branch not found");
			}
		} else {
			// Platform-level customer: the branch names the organization;
			// only ACTIVE branches of ACTIVE organizations are reachable
			// (ADR-040). An INACTIVE branch still answers INACTIVE below —
			// the guard filters what the customer may see at all.
			if (branch.getOrganization().getStatus()
					!= com.sourabh.smartqueue.organization.OrganizationStatus.ACTIVE) {
				throw new ResourceNotFoundException(
						"BRANCH_NOT_FOUND", "Branch not found");
			}
		}

		ZoneId zone = branch.getTimeZone();
		ZonedDateTime nowInBranch = clock.instant().atZone(zone);
		LocalDate today = nowInBranch.toLocalDate();
		LocalTime localTime = nowInBranch.toLocalTime();

		// Holiday for today (full-day closure)
		Optional<Holiday> holiday = holidays.findByBranchIdAndDate(branchId, today);

		// Upcoming holiday (next date > today, ascending, first)
		Optional<Holiday> upcoming = holidays.findByBranchIdOrderByDateAsc(branchId).stream()
				.filter(h -> h.getDate().isAfter(today))
				.findFirst();

		// Working hours intervals for today's day-of-week
		List<WorkingHour> todayIntervals = workingHours.findByBranchIdAndDayOfWeek(branchId,
				today.getDayOfWeek());

		// Determine state
		String state;
		String reason;
		if (branch.getStatus() == BranchStatus.INACTIVE) {
			state = "INACTIVE";
			reason = "Branch is inactive";
		} else if (holiday.isPresent()) {
			state = "HOLIDAY";
			reason = holiday.get().getName();
		} else if (todayIntervals.isEmpty()) {
			state = "CLOSED";
			reason = "Outside working hours";
		} else if (isWithinAnyInterval(localTime, todayIntervals)) {
			state = "OPEN";
			reason = null;
		} else {
			state = "CLOSED";
			reason = "Outside working hours";
		}

		Instant now = clock.instant();

		return new ScheduleStatusResponse(
				branchId,
				zone.getId(),
				now,
				state,
				todayIntervals.stream()
						.map(AvailabilityWorkingInterval::from)
						.toList(),
				upcoming.map(h -> new UpcomingHoliday(h.getDate(), h.getName())).orElse(null),
				reason);
	}

	private boolean isWithinAnyInterval(LocalTime time, List<WorkingHour> intervals) {
		for (WorkingHour interval : intervals) {
			LocalTime opens = interval.getStartTime();
			LocalTime closes = interval.getEndTime();
			// Open when now is at or after opening and strictly before
			// closing ([opens, closes) semantics, matching booking).
			if (!time.isBefore(opens) && time.isBefore(closes)) {
				return true;
			}
		}
		return false;
	}

	public record UpcomingHoliday(LocalDate date, String name) {
	}

	public record ScheduleStatusResponse(
			UUID branchId,
			String timezone,
			Instant now,
			String state,
			List<AvailabilityWorkingInterval> todayIntervals,
			UpcomingHoliday upcomingHoliday,
			String reason) {
	}
}