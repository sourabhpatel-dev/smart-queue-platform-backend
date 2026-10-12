package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.appointment.Appointment;
import com.sourabh.smartqueue.appointment.AppointmentBookingService;
import com.sourabh.smartqueue.appointment.AppointmentConflictException;
import com.sourabh.smartqueue.appointment.AppointmentRepository;
import com.sourabh.smartqueue.appointment.AppointmentStatus;
import com.sourabh.smartqueue.appointment.BranchClosedException;
import com.sourabh.smartqueue.appointment.InvalidAppointmentRequestException;
import com.sourabh.smartqueue.appointment.OutsideWorkingHoursException;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.branch.BranchStatus;
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
 * Phase 2E booking tests: the {@link AppointmentBookingService} use case
 * against real PostgreSQL (see ADR-011). Each test rolls back.
 *
 * <p>Fixture: "City Clinic / Downtown" in Asia/Kolkata, a 30-minute
 * "General Consultation", customer Asha, Monday 2026-10-05 working hours
 * 09:00–13:00 + 14:00–18:00. Own container per test class.
 */
@SpringBootTest
@Testcontainers
@Transactional
class AppointmentBookingTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
	static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 7);
	static final LocalDate FRIDAY = LocalDate.of(2026, 12, 25);

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
	AppointmentBookingService booking;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Flyway flyway;

	Organization org;
	Branch branch;
	Service consultation;
	User asha;

	@BeforeEach
	void setUp() {
		org = organizations.save(new Organization("City Clinic"));
		branch = branches.save(new Branch(org, "Downtown", "Bhopal", "India", KOLKATA));
		consultation = services.save(new Service(branch, "General Consultation", Duration.ofMinutes(30)));
		asha = users.save(new User(org, "asha@example.com", "Asha"));
		asha.grantRole(RoleType.CUSTOMER);
		workingHours.save(new WorkingHour(branch, DayOfWeek.MONDAY,
				LocalTime.of(9, 0), LocalTime.of(13, 0)));
		workingHours.save(new WorkingHour(branch, DayOfWeek.MONDAY,
				LocalTime.of(14, 0), LocalTime.of(18, 0)));
	}

	private Instant at(LocalDate date, int hour, int minute) {
		return date.atTime(hour, minute).atZone(KOLKATA).toInstant();
	}

	/**
	 * The PostgreSQL JDBC driver does not bind {@code Instant} parameters
	 * reliably, so native-SQL tests pass instants as {@code Timestamp}.
	 */
	private static Timestamp ts(Instant instant) {
		return Timestamp.from(instant);
	}

	private Appointment book(User customer, Service service, Instant start) {
		return booking.book(customer.getId(), service.getId(), start);
	}

	@Test
	void booksAppointmentSuccessfully() {
		Appointment saved = book(asha, consultation, at(MONDAY, 10, 0));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
		assertThat(saved.getStartAt()).isEqualTo(at(MONDAY, 10, 0));
		assertThat(saved.getEndAt()).isEqualTo(at(MONDAY, 10, 30));
		assertThat(saved.getBranch().getId()).isEqualTo(branch.getId());
		assertThat(saved.getService().getId()).isEqualTo(consultation.getId());
		assertThat(saved.getCustomer().getId()).isEqualTo(asha.getId());
		assertThat(saved.getOrganization().getId()).isEqualTo(org.getId());
	}

	@Test
	void statusPersistsAsString() {
		Appointment saved = book(asha, consultation, at(MONDAY, 10, 0));

		String stored = jdbcTemplate.queryForObject(
				"SELECT status FROM appointment WHERE id = ?", String.class, saved.getId());
		assertThat(stored).isEqualTo("SCHEDULED");
	}

	@Test
	void unknownCustomerIsRejected() {
		assertThatThrownBy(() -> booking.book(UUID.randomUUID(), consultation.getId(), at(MONDAY, 10, 0)))
				.isInstanceOf(InvalidAppointmentRequestException.class);
	}

	@Test
	void unknownServiceIsRejected() {
		assertThatThrownBy(() -> booking.book(asha.getId(), UUID.randomUUID(), at(MONDAY, 10, 0)))
				.isInstanceOf(InvalidAppointmentRequestException.class);
	}

	@Test
	void inactiveBranchIsRejected() {
		branch.setStatus(BranchStatus.INACTIVE);

		assertThatThrownBy(() -> book(asha, consultation, at(MONDAY, 10, 0)))
				.isInstanceOf(InvalidAppointmentRequestException.class);
	}

	@Test
	void inactiveServiceIsRejected() {
		consultation.setStatus(ServiceStatus.INACTIVE);

		assertThatThrownBy(() -> book(asha, consultation, at(MONDAY, 10, 0)))
				.isInstanceOf(InvalidAppointmentRequestException.class);
	}

	@Test
	void inactiveCustomerIsRejected() {
		asha.setStatus(UserStatus.INACTIVE);

		assertThatThrownBy(() -> book(asha, consultation, at(MONDAY, 10, 0)))
				.isInstanceOf(InvalidAppointmentRequestException.class);
	}

	@Test
	void customerBooksServiceAtAnotherOrganization() {
		// ADR-040: customers are platform-level identities — booking at an
		// ACTIVE branch of another organization is the product working as
		// designed. The appointment records the branch's organization.
		Organization other = organizations.save(new Organization("County Hospital"));
		User outsider = users.save(new User(other, "vijay@example.com", "Vijay"));
		outsider.grantRole(RoleType.CUSTOMER);

		Appointment booked = book(outsider, consultation, at(MONDAY, 10, 0));

		assertThat(booked.getOrganization().getId()).isEqualTo(org.getId());
		assertThat(booked.getCustomer().getId()).isEqualTo(outsider.getId());
	}

	@Test
	void mismatchedBranchAndServiceAreRejectedByDomainGuard() {
		Branch uptown = branches.save(new Branch(org, "Uptown", "Bhopal", "India", KOLKATA));
		Service uptownService = services.save(new Service(uptown, "General Consultation", Duration.ofMinutes(30)));

		assertThatThrownBy(() -> new Appointment(org, branch, uptownService, asha,
				at(MONDAY, 10, 0), at(MONDAY, 10, 30)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void mismatchedBranchAndServiceAreRejectedByDatabase() {
		Branch uptown = branches.save(new Branch(org, "Uptown", "Bhopal", "India", KOLKATA));
		Service uptownService = services.save(new Service(uptown, "General Consultation", Duration.ofMinutes(30)));

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO appointment (id, organization_id, branch_id, service_id,"
						+ " customer_id, start_at, end_at, status, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
				UUID.randomUUID(), org.getId(), branch.getId(), uptownService.getId(),
				asha.getId(), ts(at(MONDAY, 10, 0)), ts(at(MONDAY, 10, 30)), "SCHEDULED"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void foreignCustomerIsRejectedByDatabase() {
		Organization other = organizations.save(new Organization("County Hospital"));
		User outsider = users.save(new User(other, "vijay@example.com", "Vijay"));

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO appointment (id, organization_id, branch_id, service_id,"
						+ " customer_id, start_at, end_at, status, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
				UUID.randomUUID(), org.getId(), branch.getId(), consultation.getId(),
				outsider.getId(), ts(at(MONDAY, 10, 0)), ts(at(MONDAY, 10, 30)), "SCHEDULED"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void endTimeIsFixedAtBooking() {
		Appointment saved = book(asha, consultation, at(MONDAY, 10, 0));
		consultation.setStandardDuration(Duration.ofMinutes(45));

		Appointment reloaded = appointments.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getEndAt()).isEqualTo(at(MONDAY, 10, 30));
	}

	@Test
	void appointmentInsideIntervalIsAccepted() {
		assertThat(book(asha, consultation, at(MONDAY, 10, 0)).getId()).isNotNull();
	}

	@Test
	void appointmentAtExactIntervalEdgesIsAccepted() {
		assertThat(book(asha, consultation, at(MONDAY, 9, 0)).getId()).isNotNull();
		assertThat(book(asha, consultation, at(MONDAY, 12, 30)).getId()).isNotNull();
	}

	@Test
	void appointmentCrossingSplitBoundaryIsRejected() {
		assertThatThrownBy(() -> book(asha, consultation, at(MONDAY, 12, 45)))
				.isInstanceOf(OutsideWorkingHoursException.class);
	}

	@Test
	void appointmentInLunchGapIsRejected() {
		assertThatThrownBy(() -> book(asha, consultation, at(MONDAY, 13, 30)))
				.isInstanceOf(OutsideWorkingHoursException.class);
	}

	@Test
	void appointmentOutsideHoursIsRejected() {
		assertThatThrownBy(() -> book(asha, consultation, at(MONDAY, 8, 0)))
				.isInstanceOf(OutsideWorkingHoursException.class);
	}

	@Test
	void appointmentOnClosedWeekdayIsRejected() {
		assertThatThrownBy(() -> book(asha, consultation, at(WEDNESDAY, 10, 0)))
				.isInstanceOf(OutsideWorkingHoursException.class);
	}

	@Test
	void branchLocalTimezoneIsUsedForWorkingHours() {
		// 08:30 IST is 03:00 UTC — before opening. A server-local reading of
		// the UTC instant would misplace it; the branch zone decides.
		assertThatThrownBy(() -> booking.book(asha.getId(), consultation.getId(),
				MONDAY.atTime(3, 0).atZone(ZoneId.of("UTC")).toInstant()))
				.isInstanceOf(OutsideWorkingHoursException.class);
		// 09:00 IST sharp is accepted.
		assertThat(booking.book(asha.getId(), consultation.getId(),
				MONDAY.atTime(3, 30).atZone(ZoneId.of("UTC")).toInstant()).getId()).isNotNull();
	}

	@Test
	void appointmentOnHolidayIsRejected() {
		workingHours.save(new WorkingHour(branch, DayOfWeek.FRIDAY,
				LocalTime.of(9, 0), LocalTime.of(13, 0)));
		holidays.save(new com.sourabh.smartqueue.holiday.Holiday(
				branch, FRIDAY, "Christmas Day"));

		assertThatThrownBy(() -> book(asha, consultation, at(FRIDAY, 10, 0)))
				.isInstanceOf(BranchClosedException.class);
	}

	@Test
	void otherBranchHolidayDoesNotBlock() {
		workingHours.save(new WorkingHour(branch, DayOfWeek.FRIDAY,
				LocalTime.of(9, 0), LocalTime.of(13, 0)));
		Branch uptown = branches.save(new Branch(org, "Uptown", "Bhopal", "India", KOLKATA));
		holidays.save(new com.sourabh.smartqueue.holiday.Holiday(
				uptown, FRIDAY, "Local Festival"));

		assertThat(book(asha, consultation, at(FRIDAY, 10, 0)).getId()).isNotNull();
	}

	@Test
	void sameCustomerOverlapIsRejected() {
		book(asha, consultation, at(MONDAY, 10, 0));

		assertThatThrownBy(() -> book(asha, consultation, at(MONDAY, 10, 15)))
				.isInstanceOf(AppointmentConflictException.class);
	}

	@Test
	void adjacentAppointmentIsAllowed() {
		book(asha, consultation, at(MONDAY, 10, 0));

		assertThat(book(asha, consultation, at(MONDAY, 10, 30)).getId()).isNotNull();
	}

	@Test
	void sameServiceOverlapAcrossCustomersIsRejected() {
		book(asha, consultation, at(MONDAY, 10, 0));

		assertThatThrownBy(() -> book(secondCustomer(), consultation, at(MONDAY, 10, 15)))
				.isInstanceOf(AppointmentConflictException.class);
	}

	@Test
	void differentServiceAndCustomerAtSameTimeIsAllowed() {
		// Proves the branch is NOT serialized: two simultaneous appointments
		// for different services/customers coexist. (Same customer could not
		// be in two places at once — that stays a conflict.)
		Service bloodTest = services.save(new Service(branch, "Blood Test", Duration.ofMinutes(30)));
		book(asha, consultation, at(MONDAY, 10, 0));

		assertThat(book(secondCustomer(), bloodTest, at(MONDAY, 10, 0)).getId()).isNotNull();
	}

	@Test
	void cancelledAppointmentFreesTheSlot() {
		Appointment booked = book(asha, consultation, at(MONDAY, 10, 0));
		booked.cancel();
		appointments.saveAndFlush(booked);

		assertThat(book(secondCustomer(), consultation, at(MONDAY, 10, 0)).getId()).isNotNull();
	}

	@Test
	void completedAppointmentDoesNotBlock() {
		Appointment booked = book(asha, consultation, at(MONDAY, 10, 0));
		booked.complete();
		appointments.saveAndFlush(booked);

		assertThat(book(secondCustomer(), consultation, at(MONDAY, 10, 0)).getId()).isNotNull();
	}

	@Test
	void noShowAppointmentDoesNotBlock() {
		Appointment booked = book(asha, consultation, at(MONDAY, 10, 0));
		booked.markNoShow();
		appointments.saveAndFlush(booked);

		assertThat(book(secondCustomer(), consultation, at(MONDAY, 10, 0)).getId()).isNotNull();
	}

	@Test
	void terminalTransitionTwiceIsRejected() {
		Appointment booked = book(asha, consultation, at(MONDAY, 10, 0));
		booked.cancel();

		assertThatThrownBy(booked::cancel).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void temporalOverlapIsRejectedByDatabase() {
		book(asha, consultation, at(MONDAY, 10, 0));

		// Bypass the service: the exclusion constraint must still hold.
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO appointment (id, organization_id, branch_id, service_id,"
						+ " customer_id, start_at, end_at, status, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
				UUID.randomUUID(), org.getId(), branch.getId(), consultation.getId(),
				asha.getId(), ts(at(MONDAY, 10, 15)), ts(at(MONDAY, 10, 45)), "SCHEDULED"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void invalidIntervalIsRejectedByDatabase() {
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO appointment (id, organization_id, branch_id, service_id,"
						+ " customer_id, start_at, end_at, status, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
				UUID.randomUUID(), org.getId(), branch.getId(), consultation.getId(),
				asha.getId(), ts(at(MONDAY, 10, 30)), ts(at(MONDAY, 10, 30)), "SCHEDULED"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void flywayAppliedV6DomainMigration() {
		assertThat(flyway.info().applied())
				.anySatisfy(applied -> assertThat(applied.getVersion().getVersion()).isEqualTo("6"));

		Integer tables = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables"
						+ " WHERE table_schema = 'public' AND table_name = 'appointment'",
				Integer.class);
		assertThat(tables).isEqualTo(1);
	}

	private int customerSequence = 0;

	private User secondCustomer() {
		customerSequence++;
		User other = users.save(new User(org,
				"customer" + customerSequence + "@example.com", "Customer " + customerSequence));
		other.grantRole(RoleType.CUSTOMER);
		return other;
	}
}
