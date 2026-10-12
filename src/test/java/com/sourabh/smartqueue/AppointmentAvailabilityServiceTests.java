package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.appointment.Appointment;
import com.sourabh.smartqueue.appointment.AppointmentAvailabilityService;
import com.sourabh.smartqueue.appointment.AppointmentBranchMismatchException;
import com.sourabh.smartqueue.appointment.AppointmentRepository;
import com.sourabh.smartqueue.appointment.InvalidAppointmentRequestException;
import com.sourabh.smartqueue.appointment.api.AvailabilityResponse;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.branch.BranchStatus;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.holiday.Holiday;
import com.sourabh.smartqueue.holiday.HolidayRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.service.Service;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.service.ServiceStatus;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.user.UserStatus;
import com.sourabh.smartqueue.workinghour.WorkingHour;
import com.sourabh.smartqueue.workinghour.WorkingHourRepository;

/**
 * Appointment availability read tests (see ADR-039).
 *
 * <p>Covers the advisory slot generation against real PostgreSQL:
 * working-interval fit, split boundaries, holiday veto, past/today
 * exclusion, service/customer conflicts, tenant isolation, inactive
 * resources, and DST-correct branch-local date handling. Each test
 * rolls back.
 */
@SpringBootTest
@Testcontainers
@Transactional
class AppointmentAvailabilityServiceTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
	static final LocalDate MONDAY = future(DayOfWeek.MONDAY);
	static final LocalDate TUESDAY = future(DayOfWeek.TUESDAY);
	static final LocalDate WEDNESDAY = future(DayOfWeek.WEDNESDAY);

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	OrganizationRepository organizations;

	@Autowired
	BranchRepository branches;

	@Autowired
	ServiceRepository services;

	@Autowired
	UserRepository users;

	@Autowired
	WorkingHourRepository workingHours;

	@Autowired
	HolidayRepository holidays;

	@Autowired
	AppointmentRepository appointments;

	@Autowired
	AppointmentAvailabilityService availability;

	Organization org;
	Organization otherOrg;
	Branch branch;
	Branch otherBranch;
	Branch closedBranch;
	Service consultation;
	Service consultationClosed;
	User customer;
	User otherCustomer;

	@BeforeEach
	void setUp() {
		org = organizations.save(new Organization("City Clinic"));
		otherOrg = organizations.save(new Organization("Other Clinic"));
		branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		otherBranch = branches.save(new Branch(otherOrg, "Uptown", "Pune", "India", KOLKATA));
		closedBranch = branches.save(new Branch(org, "Closed", "Indore", "India", KOLKATA));
		consultation = services.save(new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		consultationClosed = services.save(new Service(closedBranch, "Consultation", Duration.ofMinutes(30)));
		customer = users.save(new User(org, "asha@example.com", "Asha"));
		customer.grantRole(RoleType.CUSTOMER);
		otherCustomer = users.save(new User(otherOrg, "bob@example.com", "Bob"));
		otherCustomer.grantRole(RoleType.CUSTOMER);
		// Monday: split; every other day: single 09:00–18:00.
		workingHours.save(new WorkingHour(branch, DayOfWeek.MONDAY,
				LocalTime.of(9, 0), LocalTime.of(13, 0)));
		workingHours.save(new WorkingHour(branch, DayOfWeek.MONDAY,
				LocalTime.of(14, 0), LocalTime.of(18, 0)));
		for (DayOfWeek day : DayOfWeek.values()) {
			if (day != DayOfWeek.MONDAY) {
				workingHours.save(new WorkingHour(branch, day,
						LocalTime.of(9, 0), LocalTime.of(18, 0)));
			}
		}
	}

	/** Future calendar date landing on the given weekday (≥7 days out). */
	private static LocalDate future(DayOfWeek day) {
		LocalDate d = LocalDate.now(KOLKATA).plusDays(7);
		while (d.getDayOfWeek() != day) {
			d = d.plusDays(1);
		}
		return d;
	}

	private AuthenticatedPrincipal principal(User user) {
		return new AuthenticatedPrincipal(user.getId(), user.getOrganization().getId(),
				user.getEmail(), java.util.Set.of(RoleType.CUSTOMER));
	}

	private Instant at(LocalDate date, int hour, int minute) {
		return ZonedDateTime.of(date, LocalTime.of(hour, minute), KOLKATA).toInstant();
	}

	// -- Basic availability ---------------------------------------------------

	@Test
	void singleIntervalGeneratesSlotsAtOneMinuteSteps() {
		AvailabilityResponse response = availability.availability(
				principal(customer), branch.getId(), consultation.getId(), TUESDAY);

		assertThat(response.branchId()).isEqualTo(branch.getId());
		assertThat(response.serviceId()).isEqualTo(consultation.getId());
		assertThat(response.date()).isEqualTo(TUESDAY);
		assertThat(response.timezone()).isEqualTo("Asia/Kolkata");
		assertThat(response.serviceDurationMinutes()).isEqualTo(30);
		assertThat(response.holiday()).isNull();
		assertThat(response.workingIntervals()).hasSize(1);
		assertThat(response.workingIntervals().get(0).opensAt()).isEqualTo(LocalTime.of(9, 0));
		assertThat(response.workingIntervals().get(0).closesAt()).isEqualTo(LocalTime.of(18, 0));

		// 09:00–17:30 inclusive at 1-minute steps = 511 slots.
		assertThat(response.slots()).hasSize(511);
		assertThat(response.slots().get(0).start()).isEqualTo(at(TUESDAY, 9, 0));
		assertThat(response.slots().get(0).end()).isEqualTo(at(TUESDAY, 9, 30));
		assertThat(response.slots().get(510).start()).isEqualTo(at(TUESDAY, 17, 30));
		assertThat(response.slots().get(510).end()).isEqualTo(at(TUESDAY, 18, 0));

		// Chronological order.
		for (int i = 1; i < response.slots().size(); i++) {
			assertThat(response.slots().get(i).start())
					.isAfter(response.slots().get(i - 1).start());
		}
	}

	@Test
	void splitIntervalsGenerateSlotsPerIntervalWithoutCrossingBoundary() {
		AvailabilityResponse response = availability.availability(
				principal(customer), branch.getId(), consultation.getId(), MONDAY);

		// Monday: 09:00–13:00 (211 starts) + 14:00–18:00 (211 starts) = 422.
		assertThat(response.slots()).hasSize(422);
		// First interval: 09:00–12:30.
		assertThat(response.slots().get(0).start()).isEqualTo(at(MONDAY, 9, 0));
		assertThat(response.slots().get(210).start()).isEqualTo(at(MONDAY, 12, 30));
		// Second interval starts at 14:00.
		assertThat(response.slots().get(211).start()).isEqualTo(at(MONDAY, 14, 0));
		assertThat(response.slots().get(421).start()).isEqualTo(at(MONDAY, 17, 30));
		// No slot crosses the 13:00/14:00 gap.
		for (var slot : response.slots()) {
			LocalTime start = slot.start().atZone(KOLKATA).toLocalTime();
			LocalTime end = slot.end().atZone(KOLKATA).toLocalTime();
			boolean inFirst = !start.isBefore(LocalTime.of(9, 0)) && !end.isAfter(LocalTime.of(13, 0));
			boolean inSecond = !start.isBefore(LocalTime.of(14, 0)) && !end.isAfter(LocalTime.of(18, 0));
			assertThat(inFirst || inSecond).isTrue();
		}
	}

	@Test
	void appointmentEndingExactlyAtCloseIsAllowed() {
		AvailabilityResponse response = availability.availability(
				principal(customer), branch.getId(), consultation.getId(), TUESDAY);
		// 17:30 start → 18:00 end = exactly at close.
		var lastSlot = response.slots().get(response.slots().size() - 1);
		assertThat(lastSlot.start()).isEqualTo(at(TUESDAY, 17, 30));
		assertThat(lastSlot.end()).isEqualTo(at(TUESDAY, 18, 0));
	}

	// -- Holidays -------------------------------------------------------------

	@Test
	void holidayReturnsZeroSlotsWithHolidayInfo() {
		holidays.save(new Holiday(branch, TUESDAY, "Diwali"));

		AvailabilityResponse response = availability.availability(
				principal(customer), branch.getId(), consultation.getId(), TUESDAY);

		assertThat(response.holiday()).isNotNull();
		assertThat(response.holiday().date()).isEqualTo(TUESDAY);
		assertThat(response.holiday().name()).isEqualTo("Diwali");
		assertThat(response.slots()).isEmpty();
		// Working intervals still echoed for context.
		assertThat(response.workingIntervals()).hasSize(1);
	}

	// -- Past / today ---------------------------------------------------------

	@Test
	void pastDateReturnsZeroSlots() {
		LocalDate past = LocalDate.of(2020, 1, 1);
		AvailabilityResponse response = availability.availability(
				principal(customer), branch.getId(), consultation.getId(), past);
		assertThat(response.slots()).isEmpty();
	}

	@Test
	void todayExcludesPastTimes() {
		// Use "today" — the service uses Clock.instant() so we test
		// that slots before now are excluded. We can't control the
		// clock here, but we can verify the response is consistent:
		// if today is a working day, slots start after now.
		LocalDate today = LocalDate.now(KOLKATA);
		AvailabilityResponse response = availability.availability(
				principal(customer), branch.getId(), consultation.getId(), today);
		Instant now = Instant.now();
		for (var slot : response.slots()) {
			assertThat(slot.start()).isAfter(now);
		}
	}

	// -- Existing appointments ------------------------------------------------

	@Test
	void serviceConflictRemovesOverlappingCandidates() {
		// Book 10:00–10:30 on Tuesday.
		appointments.saveAndFlush(new Appointment(org, branch, consultation, customer,
				at(TUESDAY, 10, 0), at(TUESDAY, 10, 30)));

		AvailabilityResponse response = availability.availability(
				principal(customer), branch.getId(), consultation.getId(), TUESDAY);

		// Slots overlapping 10:00–10:30 are gone: 09:30–10:00 through 10:00–10:30.
		for (var slot : response.slots()) {
			boolean overlaps = slot.start().isBefore(at(TUESDAY, 10, 30))
					&& at(TUESDAY, 10, 0).isBefore(slot.end());
			assertThat(overlaps).isFalse();
		}
		// 09:30 start (ends 10:00) is still available — boundary touch.
		assertThat(response.slots().stream()
				.anyMatch(s -> s.start().equals(at(TUESDAY, 9, 30)))).isTrue();
		// 10:30 start (ends 11:00) is still available — boundary touch.
		assertThat(response.slots().stream()
				.anyMatch(s -> s.start().equals(at(TUESDAY, 10, 30)))).isTrue();
	}

	@Test
	void customerConflictOnDifferentServiceRemovesCandidates() {
		// Book a different service for the same customer at 10:00–10:30.
		Service other = services.save(new Service(branch, "Dental", Duration.ofMinutes(30)));
		appointments.saveAndFlush(new Appointment(org, branch, other, customer,
				at(TUESDAY, 10, 0), at(TUESDAY, 10, 30)));

		AvailabilityResponse response = availability.availability(
				principal(customer), branch.getId(), consultation.getId(), TUESDAY);

		// Customer conflict applies across services.
		for (var slot : response.slots()) {
			boolean overlaps = slot.start().isBefore(at(TUESDAY, 10, 30))
					&& at(TUESDAY, 10, 0).isBefore(slot.end());
			assertThat(overlaps).isFalse();
		}
	}

	@Test
	void cancelledAppointmentDoesNotBlock() {
		Appointment cancelled = new Appointment(org, branch, consultation, customer,
				at(TUESDAY, 10, 0), at(TUESDAY, 10, 30));
		cancelled.cancel();
		appointments.saveAndFlush(cancelled);

		AvailabilityResponse response = availability.availability(
				principal(customer), branch.getId(), consultation.getId(), TUESDAY);

		// Cancelled is terminal — slot at 10:00 is available.
		assertThat(response.slots().stream()
				.anyMatch(s -> s.start().equals(at(TUESDAY, 10, 0)))).isTrue();
	}

	@Test
	void completedAppointmentDoesNotBlock() {
		Appointment completed = new Appointment(org, branch, consultation, customer,
				at(TUESDAY, 10, 0), at(TUESDAY, 10, 30));
		completed.complete();
		appointments.saveAndFlush(completed);

		AvailabilityResponse response = availability.availability(
				principal(customer), branch.getId(), consultation.getId(), TUESDAY);

		assertThat(response.slots().stream()
				.anyMatch(s -> s.start().equals(at(TUESDAY, 10, 0)))).isTrue();
	}

	@Test
	void boundaryTouchingAppointmentDoesNotConflict() {
		// Book 10:00–10:30. A slot at 09:30–10:00 touches but doesn't overlap.
		appointments.saveAndFlush(new Appointment(org, branch, consultation, customer,
				at(TUESDAY, 10, 0), at(TUESDAY, 10, 30)));

		AvailabilityResponse response = availability.availability(
				principal(customer), branch.getId(), consultation.getId(), TUESDAY);

		// 09:30 start (ends 10:00) — boundary touch, not overlap.
		assertThat(response.slots().stream()
				.anyMatch(s -> s.start().equals(at(TUESDAY, 9, 30)))).isTrue();
	}

	// -- Tenant isolation -----------------------------------------------------

	@Test
	void customerFromAnotherOrganizationReadsAvailability() {
		// ADR-040: a platform-level customer belongs to no organization —
		// availability at any ACTIVE branch is readable.
		AvailabilityResponse response = availability.availability(
				principal(otherCustomer), branch.getId(), consultation.getId(), TUESDAY);

		assertThat(response.branchId()).isEqualTo(branch.getId());
	}

	@Test
	void serviceFromAnotherBranchIsRejected() {
		Branch sameOrgOther = branches.save(new Branch(org, "Other", "Indore", "India", KOLKATA));
		Service foreign = services.save(new Service(sameOrgOther, "Foreign", Duration.ofMinutes(30)));
		assertThatThrownBy(() -> availability.availability(
				principal(customer), branch.getId(), foreign.getId(), TUESDAY))
				.isInstanceOf(AppointmentBranchMismatchException.class);
	}

	@Test
	void inactiveBranchIsRejected() {
		branch.setStatus(BranchStatus.INACTIVE);
		branches.save(branch);
		assertThatThrownBy(() -> availability.availability(
				principal(customer), branch.getId(), consultation.getId(), TUESDAY))
				.isInstanceOf(InvalidAppointmentRequestException.class)
				.hasMessageContaining("branch is not active");
	}

	@Test
	void inactiveServiceIsRejected() {
		consultation.setStatus(ServiceStatus.INACTIVE);
		services.save(consultation);
		assertThatThrownBy(() -> availability.availability(
				principal(customer), branch.getId(), consultation.getId(), TUESDAY))
				.isInstanceOf(InvalidAppointmentRequestException.class)
				.hasMessageContaining("service is not active");
	}

	@Test
	void inactiveCustomerIsRejected() {
		customer.setStatus(UserStatus.INACTIVE);
		users.save(customer);
		assertThatThrownBy(() -> availability.availability(
				principal(customer), branch.getId(), consultation.getId(), TUESDAY))
				.isInstanceOf(InvalidAppointmentRequestException.class)
				.hasMessageContaining("customer is not active");
	}

	// -- Timezone / DST -------------------------------------------------------

	@Test
	void branchTimezoneDiffersFromServer() {
		// The service uses the branch zone, not the server zone.
		// Asia/Kolkata is UTC+5:30 — a 09:00 local start is 03:30Z.
		AvailabilityResponse response = availability.availability(
				principal(customer), branch.getId(), consultation.getId(), TUESDAY);
		Instant firstStart = response.slots().get(0).start();
		// Verify it's 09:00 Kolkata = 03:30 UTC.
		assertThat(firstStart).isEqualTo(at(TUESDAY, 9, 0));
		assertThat(firstStart.atZone(ZoneId.of("UTC")).toLocalTime())
				.isEqualTo(LocalTime.of(3, 30));
	}

	@Test
	void dstGapSkipsNonexistentLocalStart() {
		// America/New_York: DST spring-forward 2026-03-08 02:00→03:00.
		// A branch in that zone with hours 01:00–05:00 on that day
		// should skip 02:00–02:59 (nonexistent).
		Branch nyBranch = branches.save(new Branch(org, "NYC", "New York", "USA", NEW_YORK));
		Service nyService = services.save(new Service(nyBranch, "Consult", Duration.ofMinutes(30)));
		LocalDate dstDay = LocalDate.of(2027, 3, 14);
		workingHours.save(new WorkingHour(nyBranch, DayOfWeek.SUNDAY,
				LocalTime.of(1, 0), LocalTime.of(5, 0)));

		AvailabilityResponse response = availability.availability(
				principal(customer), nyBranch.getId(), nyService.getId(), dstDay);

		// Slots should exist but none should start in the 02:00–02:59 gap.
		assertThat(response.slots()).isNotEmpty();
		for (var slot : response.slots()) {
			LocalTime start = slot.start().atZone(NEW_YORK).toLocalTime();
			assertThat(start.isBefore(LocalTime.of(2, 0)) || !start.isBefore(LocalTime.of(3, 0)))
					.isTrue();
		}
	}

	@Test
	void dstOverlapKeepsAmbiguousLocalStart() {
		// America/New_York: DST fall-back 2026-11-01 02:00→01:00.
		// 01:30 is ambiguous — it round-trips and stays bookable.
		Branch nyBranch = branches.save(new Branch(org, "NYC2", "New York", "USA", NEW_YORK));
		Service nyService = services.save(new Service(nyBranch, "Consult", Duration.ofMinutes(30)));
		LocalDate dstDay = LocalDate.of(2026, 11, 1);
		workingHours.save(new WorkingHour(nyBranch, DayOfWeek.SUNDAY,
				LocalTime.of(1, 0), LocalTime.of(5, 0)));

		AvailabilityResponse response = availability.availability(
				principal(customer), nyBranch.getId(), nyService.getId(), dstDay);

		// 01:30 should be present (ambiguous but round-trips).
		assertThat(response.slots().stream()
				.anyMatch(s -> s.start().atZone(NEW_YORK).toLocalTime().equals(LocalTime.of(1, 30))))
				.isTrue();
	}

	// -- Closed weekday -------------------------------------------------------

	@Test
	void closedWeekdayReturnsZeroSlotsWithEmptyIntervals() {
		// closedBranch has no working hours at all.
		AvailabilityResponse response = availability.availability(
				principal(customer), closedBranch.getId(), consultationClosed.getId(), WEDNESDAY);
		assertThat(response.slots()).isEmpty();
		assertThat(response.workingIntervals()).isEmpty();
		assertThat(response.holiday()).isNull();
	}
}
