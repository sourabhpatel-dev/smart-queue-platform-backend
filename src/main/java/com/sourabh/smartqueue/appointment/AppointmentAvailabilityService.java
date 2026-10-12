package com.sourabh.smartqueue.appointment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.appointment.api.AvailabilityHoliday;
import com.sourabh.smartqueue.appointment.api.AvailabilityResponse;
import com.sourabh.smartqueue.appointment.api.AvailabilitySlot;
import com.sourabh.smartqueue.appointment.api.AvailabilityWorkingInterval;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.branch.BranchStatus;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.holiday.Holiday;
import com.sourabh.smartqueue.holiday.HolidayRepository;
import com.sourabh.smartqueue.service.ServiceStatus;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.user.UserStatus;
import com.sourabh.smartqueue.workinghour.WorkingHour;
import com.sourabh.smartqueue.workinghour.WorkingHourRepository;

/**
 * The appointment availability read (see ADR-039).
 *
 * <p>Advisory continuous free-time search over the exact booking
 * predicates: branch-local date in the branch zone, full-day holiday
 * veto, whole-interval fit inside one working interval (split
 * boundaries never crossed), strict-future start (same rule as
 * booking), and half-open overlap against the caller's active
 * appointments — per service and per customer, terminal states never
 * blocking. No rule is redefined here; the booking transaction
 * (exclusion constraints included) stays authoritative and a
 * returned slot may lose a concurrent race (surfaced as the usual
 * {@code 409} at booking).
 *
 * <p>Candidate step is one minute — derived from the domain's own
 * minute granularity (working hours reject seconds), not a booking
 * increment: there is deliberately no slot grid, so every bookable
 * minute is listed. Read-only: two bounded day-window queries plus
 * in-memory filtering, no writes, no cache, no new index (the
 * branch-time and exclusion indexes serve the reads).
 *
 * <p>Timezone policy follows booking (instant-based, no second
 * policy): candidates are branch-local starts resolved with
 * {@code java.time} gap/overlap semantics, verified by round-trip —
 * a nonexistent local start (spring-forward gap) resolves away from
 * itself and is skipped; an ambiguous start (fall-back overlap)
 * round-trips and stays bookable, exactly as booking it would.
 */
@Service
public class AppointmentAvailabilityService {

	private static final EnumSet<AppointmentStatus> ACTIVE =
			EnumSet.of(AppointmentStatus.SCHEDULED, AppointmentStatus.CONFIRMED);

	private final AppointmentRepository appointments;
	private final BranchRepository branches;
	private final com.sourabh.smartqueue.service.ServiceRepository services;
	private final UserRepository users;
	private final WorkingHourRepository workingHours;
	private final HolidayRepository holidays;
	private final Clock clock;

	public AppointmentAvailabilityService(AppointmentRepository appointments,
			BranchRepository branches,
			com.sourabh.smartqueue.service.ServiceRepository services,
			UserRepository users,
			WorkingHourRepository workingHours,
			HolidayRepository holidays,
			Clock clock) {
		this.appointments = appointments;
		this.branches = branches;
		this.services = services;
		this.users = users;
		this.workingHours = workingHours;
		this.holidays = holidays;
		this.clock = clock;
	}

	/**
	 * @throws ResourceNotFoundException for unknown ids or foreign
	 * organization (scope-safe {@code 404}).
	 * @throws AppointmentBranchMismatchException when the service
	 * belongs to another branch ({@code 422}, same as booking).
	 * @throws InvalidAppointmentRequestException for inactive
	 * branch/service/customer ({@code 400}, same as booking — an
	 * inactive resource exposes no bookable availability).
	 */
	@Transactional(readOnly = true)
	public AvailabilityResponse availability(AuthenticatedPrincipal principal,
			UUID branchId, UUID serviceId, LocalDate date) {
		Branch branch = branches.findById(branchId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"BRANCH_NOT_FOUND", "Branch not found"));
		if (principal.isOperational()) {
			if (!branch.getOrganization().getId().equals(principal.organizationId())) {
				throw new ResourceNotFoundException("BRANCH_NOT_FOUND", "Branch not found");
			}
			// ADR-041: operators of a non-ACTIVE organization (pending,
			// rejected, deactivated) see no availability — membership
			// alone is not liveness.
			if (branch.getOrganization().getStatus()
					!= com.sourabh.smartqueue.organization.OrganizationStatus.ACTIVE) {
				throw new ResourceNotFoundException("BRANCH_NOT_FOUND", "Branch not found");
			}
		} else {
			// Platform-level customer: the branch names the organization.
			// Only the organization's liveness filters here (404); an
			// inactive branch still answers 400 below, exactly like the
			// documented booking contract.
			if (branch.getOrganization().getStatus()
					!= com.sourabh.smartqueue.organization.OrganizationStatus.ACTIVE) {
				throw new ResourceNotFoundException("BRANCH_NOT_FOUND", "Branch not found");
			}
		}
		com.sourabh.smartqueue.service.Service service = services.findById(serviceId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"SERVICE_NOT_FOUND", "Service not found"));
		if (principal.isOperational()
				&& !service.getBranch().getOrganization().getId()
						.equals(principal.organizationId())) {
			throw new ResourceNotFoundException("SERVICE_NOT_FOUND", "Service not found");
		}
		if (!service.getBranch().getId().equals(branch.getId())) {
			throw new AppointmentBranchMismatchException(
					"Service belongs to another branch and cannot be booked here");
		}
		User customer = users.findById(principal.userId())
				.orElseThrow(() -> new ResourceNotFoundException(
						"USER_NOT_FOUND", "User not found"));
		if (branch.getStatus() != BranchStatus.ACTIVE) {
			throw new InvalidAppointmentRequestException(
					"branch is not active: " + branch.getId());
		}
		if (service.getStatus() != ServiceStatus.ACTIVE) {
			throw new InvalidAppointmentRequestException(
					"service is not active: " + service.getId());
		}
		if (customer.getStatus() != UserStatus.ACTIVE) {
			throw new InvalidAppointmentRequestException(
					"customer is not active: " + customer.getId());
		}
		// No customer↔organization equality for platform-level customers
		// (ADR-040); the availability conflicts below are pinned to the
		// customer and the service, which is what booking arbitrates.

		ZoneId zone = branch.getTimeZone();
		Duration duration = service.getStandardDuration();
		Holiday holiday = holidays.findByBranchIdAndDate(branch.getId(), date)
				.orElse(null);
		List<WorkingHour> intervals = workingHours
				.findByBranchIdAndDayOfWeek(branch.getId(), date.getDayOfWeek()).stream()
				.sorted(Comparator.comparing(WorkingHour::getStartTime))
				.toList();

		List<AvailabilitySlot> slots = new ArrayList<>();
		if (holiday == null && !intervals.isEmpty()) {
			ZonedDateTime dayStart = date.atStartOfDay(zone);
			Instant from = dayStart.toInstant();
			Instant to = dayStart.plusDays(1).toInstant();
			UUID organizationId = branch.getOrganization().getId();
			List<Appointment> serviceConflicts = appointments.findActiveOverlapping(
					organizationId, service.getId(), null, ACTIVE, from, to);
			List<Appointment> customerConflicts = appointments.findActiveOverlapping(
					organizationId, null, customer.getId(), ACTIVE, from, to);
			Instant now = clock.instant();
			for (WorkingHour interval : intervals) {
				collectSlots(date, zone, duration, interval, now,
						serviceConflicts, customerConflicts, slots);
			}
		}

		return new AvailabilityResponse(
				branch.getId(),
				service.getId(),
				date,
				zone.getId(),
				Math.toIntExact(duration.toMinutes()),
				holiday == null ? null : AvailabilityHoliday.from(holiday),
				intervals.stream().map(AvailabilityWorkingInterval::from).toList(),
				List.copyOf(slots));
	}

	private void collectSlots(LocalDate date, ZoneId zone, Duration duration,
			WorkingHour interval, Instant now,
			List<Appointment> serviceConflicts, List<Appointment> customerConflicts,
			List<AvailabilitySlot> slots) {
		LocalTime candidate = interval.getStartTime();
		while (!candidate.plus(duration).isAfter(interval.getEndTime())) {
			ZonedDateTime resolved = LocalDateTime.of(date, candidate).atZone(zone);
			// Nonexistent local starts (DST spring-forward gap) resolve
			// away from themselves — skip; ambiguous starts round-trip
			// and stay bookable, exactly as booking them would.
			if (resolved.toLocalDate().equals(date)
					&& resolved.toLocalTime().equals(candidate)) {
				Instant start = resolved.toInstant();
				if (start.isAfter(now)) {
					Instant end = start.plus(duration);
					if (!overlaps(serviceConflicts, start, end)
							&& !overlaps(customerConflicts, start, end)) {
						slots.add(new AvailabilitySlot(start, end));
					}
				}
			}
			candidate = candidate.plusMinutes(1);
		}
	}

	/**
	 * Half-open overlap ({@code [start, end)}), mirroring the
	 * {@code tstzrange(start_at, end_at, '[)')} exclusion semantics:
	 * boundary-touching neighbors never conflict.
	 */
	private boolean overlaps(List<Appointment> existing, Instant start, Instant end) {
		for (Appointment appointment : existing) {
			if (appointment.getStartAt().isBefore(end)
					&& start.isBefore(appointment.getEndAt())) {
				return true;
			}
		}
		return false;
	}
}


