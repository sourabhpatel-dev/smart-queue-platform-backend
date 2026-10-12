package com.sourabh.smartqueue.appointment;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.appointment.event.AppointmentBooked;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchStatus;
import com.sourabh.smartqueue.holiday.HolidayRepository;
import com.sourabh.smartqueue.service.ServiceStatus;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.UserStatus;
import com.sourabh.smartqueue.workinghour.WorkingHour;
import com.sourabh.smartqueue.workinghour.WorkingHourRepository;

/**
 * The appointment booking use case (see ADR-011).
 *
 * <p>One transactional method: validate branch/service/customer, derive the
 * interval from the service's standard duration, prove it against holidays
 * and working hours in the branch's time zone, then persist. Correctness
 * under concurrency comes from the database exclusion constraints — the
 * pre-checks exist for clear error messages, never as the concurrency
 * mechanism. A constraint rejection surfaces as
 * {@link AppointmentConflictException}, never as a PostgreSQL detail.
 *
 * <p>Deliberately narrow: booking only. Cancellation lives on the entity;
 * availability listing, rescheduling, and notifications are later phases.
 */
@Service
public class AppointmentBookingService {

	private final com.sourabh.smartqueue.service.ServiceRepository services;
	private final UserRepository users;
	private final WorkingHourRepository workingHours;
	private final HolidayRepository holidays;
	private final AppointmentRepository appointments;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	public AppointmentBookingService(
			com.sourabh.smartqueue.service.ServiceRepository services,
			UserRepository users,
			WorkingHourRepository workingHours,
			HolidayRepository holidays,
			AppointmentRepository appointments,
			ApplicationEventPublisher events,
			Clock clock) {
		this.services = services;
		this.users = users;
		this.workingHours = workingHours;
		this.holidays = holidays;
		this.appointments = appointments;
		this.events = events;
		this.clock = clock;
	}

	/**
	 * Books {@code [startAt, startAt + service duration)} for the customer.
	 *
	 * @throws InvalidAppointmentRequestException for unknown ids, inactive
	 * branch/service/customer, or cross-organization mismatches.
	 * @throws BranchClosedException if the branch-local date is a holiday.
	 * @throws OutsideWorkingHoursException if the interval does not fit
	 * inside one working-hour interval.
	 * @throws AppointmentConflictException if the slot is already taken
	 * (including by a concurrent booking that commits first).
	 */
	@Transactional
	public Appointment book(UUID customerId, UUID serviceId, Instant startAt) {
		User customer = users.findById(customerId)
				.orElseThrow(() -> new InvalidAppointmentRequestException(
						"unknown customer: " + customerId));
		com.sourabh.smartqueue.service.Service service = services.findById(serviceId)
				.orElseThrow(() -> new InvalidAppointmentRequestException(
						"unknown service: " + serviceId));
		Branch branch = service.getBranch();

		// ADR-041: only ACTIVE organizations accept bookings. Pending and
		// rejected applications (and deactivated organizations) refuse
		// here too — the service-layer scope checks deny first for
		// customers, but this domain backstop holds for every caller,
		// including direct service invocations.
		if (branch.getOrganization().getStatus()
				!= com.sourabh.smartqueue.organization.OrganizationStatus.ACTIVE) {
			throw new InvalidAppointmentRequestException(
					"organization is not active: " + branch.getOrganization().getId());
		}
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
		// ADR-040: a customer (CUSTOMER-only role) books at any ACTIVE
		// branch — the row's organization is the branch's, and neither a
		// legacy organization value nor the absence of one is proof of
		// membership. The equality backstop below therefore applies only
		// to operational principals booking for themselves, where the
		// service layer has already scoped the branch to their
		// membership-resolved organization.
		boolean operational = customer.getRoles().stream()
				.anyMatch(role -> role != RoleType.CUSTOMER);
		if (operational && customer.getOrganization() != null
				&& !customer.getOrganization().getId().equals(branch.getOrganization().getId())) {
			throw new InvalidAppointmentRequestException(
					"customer and service belong to different organizations");
		}

		Instant endAt = startAt.plus(service.getStandardDuration());
		ZoneId zone = branch.getTimeZone();
		LocalDate localDate = startAt.atZone(zone).toLocalDate();

		if (holidays.existsByBranchIdAndDate(branch.getId(), localDate)) {
			throw new BranchClosedException(
					"branch is closed on " + localDate + " (holiday)");
		}

		LocalTime localStart = startAt.atZone(zone).toLocalTime();
		LocalTime localEnd = endAt.atZone(zone).toLocalTime();
		List<WorkingHour> intervals = workingHours.findByBranchIdAndDayOfWeek(
				branch.getId(), localDate.getDayOfWeek());
		boolean fits = intervals.stream().anyMatch(
				interval -> !localStart.isBefore(interval.getStartTime())
						&& !localEnd.isAfter(interval.getEndTime()));
		if (!fits) {
			throw new OutsideWorkingHoursException(
					"interval " + localStart + "–" + localEnd + " is outside working hours on "
							+ localDate.getDayOfWeek());
		}

		Appointment appointment = new Appointment(
				branch.getOrganization(), branch, service, customer, startAt, endAt);
		try {
			Appointment saved = appointments.saveAndFlush(appointment);
			events.publishEvent(new AppointmentBooked(UUID.randomUUID(), clock.instant(),
					branch.getOrganization().getId(), branch.getId(), saved.getId(),
					service.getId(), customer.getId(), startAt, endAt, saved.getStatus()));
			return saved;
		} catch (DataIntegrityViolationException e) {
			throw new AppointmentConflictException(
					"requested slot conflicts with an existing appointment", e);
		}
	}
}

