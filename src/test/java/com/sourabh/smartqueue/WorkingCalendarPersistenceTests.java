package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import org.flywaydb.core.Flyway;
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

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.holiday.Holiday;
import com.sourabh.smartqueue.holiday.HolidayRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.workinghour.WorkingHour;
import com.sourabh.smartqueue.workinghour.WorkingHourRepository;

/**
 * Phase 2D domain tests: WorkingHour intervals and Holiday closures against
 * real PostgreSQL (see ADR-010). Each test rolls back.
 *
 * <p>Own container per test class (see the note in
 * {@code PostgresInfrastructureTests} on why it is not inherited).
 */
@SpringBootTest
@Testcontainers
@Transactional
class WorkingCalendarPersistenceTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	OrganizationRepository organizations;

	@Autowired
	BranchRepository branches;

	@Autowired
	WorkingHourRepository workingHours;

	@Autowired
	HolidayRepository holidays;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Flyway flyway;

	private Branch newBranch(String orgName, String branchName) {
		Organization org = organizations.save(new Organization(orgName));
		return branches.save(new Branch(org, branchName, "Springfield", "India", ZoneId.of("Asia/Kolkata")));
	}

	private WorkingHour newInterval(Branch branch, DayOfWeek day, String start, String end) {
		// saveAndFlush: assertions below read through JdbcTemplate, which only
		// sees flushed rows (and flush is what exercises the CHECK/exclusion).
		return workingHours.saveAndFlush(
				new WorkingHour(branch, day, LocalTime.parse(start), LocalTime.parse(end)));
	}

	@Test
	void persistsWorkingHour() {
		Branch branch = newBranch("City Clinic", "Downtown");
		WorkingHour saved = newInterval(branch, DayOfWeek.MONDAY, "09:00", "13:00");

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isNotNull();

		WorkingHour reloaded = workingHours.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getBranch().getId()).isEqualTo(branch.getId());
		assertThat(reloaded.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
		assertThat(reloaded.getStartTime()).isEqualTo(LocalTime.of(9, 0));
		assertThat(reloaded.getEndTime()).isEqualTo(LocalTime.of(13, 0));
	}

	@Test
	void dayOfWeekPersistsAsString() {
		Branch branch = newBranch("City Clinic", "Downtown");
		WorkingHour saved = newInterval(branch, DayOfWeek.THURSDAY, "09:00", "13:00");

		String stored = jdbcTemplate.queryForObject(
				"SELECT day_of_week FROM working_hour WHERE id = ?", String.class, saved.getId());
		assertThat(stored).isEqualTo("THURSDAY");
	}

	@Test
	void multipleIntervalsOnSameDayAreAllowed() {
		Branch branch = newBranch("City Clinic", "Downtown");
		newInterval(branch, DayOfWeek.MONDAY, "09:00", "13:00");

		WorkingHour afternoon = newInterval(branch, DayOfWeek.MONDAY, "14:00", "18:00");

		assertThat(afternoon.getId()).isNotNull();
	}

	@Test
	void adjacentIntervalsAreAllowed() {
		Branch branch = newBranch("City Clinic", "Downtown");
		newInterval(branch, DayOfWeek.MONDAY, "09:00", "13:00");

		// Touching at 13:00 is adjacency, not overlap ([start, end) semantics).
		WorkingHour next = newInterval(branch, DayOfWeek.MONDAY, "13:00", "14:00");

		assertThat(next.getId()).isNotNull();
	}

	@Test
	void exactDuplicateIntervalIsRejected() {
		Branch branch = newBranch("City Clinic", "Downtown");
		newInterval(branch, DayOfWeek.MONDAY, "09:00", "13:00");

		assertThatThrownBy(
				() -> workingHours.saveAndFlush(
						new WorkingHour(branch, DayOfWeek.MONDAY,
								LocalTime.of(9, 0), LocalTime.of(13, 0))))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void overlappingIntervalsAreRejected() {
		Branch branch = newBranch("City Clinic", "Downtown");
		newInterval(branch, DayOfWeek.MONDAY, "09:00", "13:00");

		assertThatThrownBy(
				() -> workingHours.saveAndFlush(
						new WorkingHour(branch, DayOfWeek.MONDAY,
								LocalTime.of(12, 0), LocalTime.of(17, 0))))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void containedIntervalIsRejected() {
		Branch branch = newBranch("City Clinic", "Downtown");
		newInterval(branch, DayOfWeek.MONDAY, "09:00", "18:00");

		assertThatThrownBy(
				() -> workingHours.saveAndFlush(
						new WorkingHour(branch, DayOfWeek.MONDAY,
								LocalTime.of(10, 0), LocalTime.of(11, 0))))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void equalStartAndEndIsRejectedByDomainGuard() {
		Branch branch = newBranch("City Clinic", "Downtown");

		assertThatThrownBy(() -> new WorkingHour(branch, DayOfWeek.MONDAY,
				LocalTime.of(9, 0), LocalTime.of(9, 0)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void reversedRangeIsRejectedByDomainGuard() {
		Branch branch = newBranch("City Clinic", "Downtown");

		assertThatThrownBy(() -> new WorkingHour(branch, DayOfWeek.MONDAY,
				LocalTime.of(18, 0), LocalTime.of(9, 0)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void subMinuteTimesAreRejectedByDomainGuard() {
		Branch branch = newBranch("City Clinic", "Downtown");

		// Schedules are minute-granular: sub-minute values would truncate in
		// the overlap expressions, so they are refused up front.
		assertThatThrownBy(() -> new WorkingHour(branch, DayOfWeek.MONDAY,
				LocalTime.of(9, 0, 30), LocalTime.of(10, 0)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void invalidRangeIsRejectedByDatabaseCheck() {
		Branch branch = newBranch("City Clinic", "Downtown");

		// Bypass the guard with raw SQL: the CHECK constraint must still hold.
		// (LocalTime args — not strings — so the driver binds TIME, not VARCHAR.)
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO working_hour"
						+ " (id, branch_id, day_of_week, start_time, end_time, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
				UUID.randomUUID(), branch.getId(), "MONDAY",
				LocalTime.of(18, 0), LocalTime.of(9, 0)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void overlappingIntervalIsRejectedByDatabase() {
		Branch branch = newBranch("City Clinic", "Downtown");
		newInterval(branch, DayOfWeek.MONDAY, "09:00", "13:00");

		// Bypass JPA entirely: the exclusion constraint must still hold.
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO working_hour"
						+ " (id, branch_id, day_of_week, start_time, end_time, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
				UUID.randomUUID(), branch.getId(), "MONDAY",
				LocalTime.of(12, 0), LocalTime.of(15, 0)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void sameScheduleWorksIndependentlyAcrossBranches() {
		Branch first = newBranch("City Clinic", "Downtown");
		Branch second = newBranch("City Clinic", "Uptown");
		newInterval(first, DayOfWeek.MONDAY, "09:00", "13:00");

		WorkingHour sameElsewhere = newInterval(second, DayOfWeek.MONDAY, "09:00", "13:00");

		assertThat(sameElsewhere.getId()).isNotNull();
	}

	@Test
	void workingHourRequiresExistingBranch() {
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO working_hour"
						+ " (id, branch_id, day_of_week, start_time, end_time, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
				UUID.randomUUID(), UUID.randomUUID(), "MONDAY",
				LocalTime.of(9, 0), LocalTime.of(13, 0)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void persistsHoliday() {
		Branch branch = newBranch("City Clinic", "Downtown");
		Holiday saved = holidays.save(
				new Holiday(branch, LocalDate.of(2026, 12, 25), "Christmas Day"));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isNotNull();

		Holiday reloaded = holidays.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getBranch().getId()).isEqualTo(branch.getId());
		assertThat(reloaded.getDate()).isEqualTo(LocalDate.of(2026, 12, 25));
		assertThat(reloaded.getName()).isEqualTo("Christmas Day");
	}

	@Test
	void sameHolidayDateAcrossBranchesIsAllowed() {
		Branch first = newBranch("City Clinic", "Downtown");
		Branch second = newBranch("City Clinic", "Uptown");
		holidays.save(new Holiday(first, LocalDate.of(2026, 12, 25), "Christmas Day"));

		Holiday sameDateElsewhere =
				holidays.save(new Holiday(second, LocalDate.of(2026, 12, 25), "Christmas Day"));

		assertThat(sameDateElsewhere.getId()).isNotNull();
	}

	@Test
	void duplicateHolidayDateWithinBranchIsRejected() {
		Branch branch = newBranch("City Clinic", "Downtown");
		holidays.save(new Holiday(branch, LocalDate.of(2026, 1, 26), "Republic Day"));

		assertThatThrownBy(() -> holidays.saveAndFlush(
				new Holiday(branch, LocalDate.of(2026, 1, 26), "Republic Day (duplicate)")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void flywayAppliedV5DomainMigration() {
		assertThat(flyway.info().applied())
				.anySatisfy(applied -> assertThat(applied.getVersion().getVersion()).isEqualTo("5"));

		Integer tables = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables"
						+ " WHERE table_schema = 'public'"
						+ " AND table_name IN ('working_hour', 'holiday')",
				Integer.class);
		assertThat(tables).isEqualTo(2);
	}
}
