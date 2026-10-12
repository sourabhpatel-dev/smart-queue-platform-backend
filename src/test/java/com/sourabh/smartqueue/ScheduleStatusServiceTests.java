package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.branch.BranchStatus;
import com.sourabh.smartqueue.branch.application.ScheduleStatusService;
import com.sourabh.smartqueue.holiday.Holiday;
import com.sourabh.smartqueue.holiday.HolidayRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.workinghour.WorkingHour;
import com.sourabh.smartqueue.workinghour.WorkingHourRepository;

@SpringBootTest
@Testcontainers
@Transactional
class ScheduleStatusServiceTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
	static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

	static class ManualClock extends Clock {
		private final AtomicReference<Instant> now;

		ManualClock(Instant start) {
			this.now = new AtomicReference<>(start);
		}

		void set(Instant instant) {
			now.set(instant);
		}

		@Override
		public Instant instant() {
			return now.get();
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException("fixed test clock");
		}
	}

	@TestConfiguration
	static class FixedClockConfiguration {
		@Bean
		@Primary
		ManualClock manualClock() {
			return new ManualClock(Instant.parse("2026-01-15T09:00:00Z"));
		}
	}

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Container
	@ServiceConnection
	static GenericContainer<?> redis =
			new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

	@Autowired
	OrganizationRepository organizations;

	@Autowired
	BranchRepository branches;

	@Autowired
	WorkingHourRepository workingHours;

	@Autowired
	HolidayRepository holidays;

	@Autowired
	ScheduleStatusService scheduleStatusService;

	@Autowired
	ManualClock clock;

	Organization org;
	Branch kolkataBranch;
	Branch newYorkBranch;
	Branch tokyoBranch;
	AuthenticatedPrincipal principal;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		org = organizations.save(new Organization("Schedule Org"));
		kolkataBranch = branches.save(new Branch(org, "Kolkata Branch", "Kolkata", "India", KOLKATA));
		newYorkBranch = branches.save(new Branch(org, "New York Branch", "New York", "USA", NEW_YORK));
		tokyoBranch = branches.save(new Branch(org, "Tokyo Branch", "Tokyo", "Japan", TOKYO));
		principal = new AuthenticatedPrincipal(UUID.randomUUID(), org.getId(),
				"test@example.com", java.util.Set.of(RoleType.CUSTOMER));
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	private void setKolkataTime(int hour, int minute) {
		clock.set(LocalDateTime.of(2026, 1, 15, hour, minute).atZone(KOLKATA).toInstant());
	}

	private void setNewYorkTime(int hour, int minute) {
		clock.set(LocalDateTime.of(2026, 1, 15, hour, minute).atZone(NEW_YORK).toInstant());
	}

	private void setTokyoTime(int hour, int minute) {
		clock.set(LocalDateTime.of(2026, 1, 15, hour, minute).atZone(TOKYO).toInstant());
	}

	private void setFullWeek(Branch branch, LocalTime opens, LocalTime closes) {
		for (DayOfWeek day : DayOfWeek.values()) {
			workingHours.save(new WorkingHour(branch, day, opens, closes));
		}
	}

	private void addSplitAfternoon(LocalTime opens, LocalTime closes) {
		for (DayOfWeek day : DayOfWeek.values()) {
			workingHours.save(new WorkingHour(kolkataBranch, day, opens, closes));
		}
	}

	private ScheduleStatusService.ScheduleStatusResponse statusOf(Branch branch) {
		return scheduleStatusService.getScheduleStatus(principal, branch.getId());
	}

	@Test
	void inactiveBranchDuringWorkingHoursReturnsInactive() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setKolkataTime(10, 0);
		kolkataBranch.setStatus(BranchStatus.INACTIVE);
		branches.save(kolkataBranch);

		var response = statusOf(kolkataBranch);

		assertThat(response.state()).isEqualTo("INACTIVE");
		assertThat(response.reason()).isEqualTo("Branch is inactive");
	}

	@Test
	void activeBranchDuringWorkingHoursReturnsOpen() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setKolkataTime(10, 0);

		var response = statusOf(kolkataBranch);

		assertThat(response.state()).isEqualTo("OPEN");
	}

	@Test
	void holidayDuringWorkingHoursReturnsHoliday() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setKolkataTime(10, 0);
		holidays.save(new Holiday(kolkataBranch, LocalDate.of(2026, 1, 15), "Republic Day"));

		var response = statusOf(kolkataBranch);

		assertThat(response.state()).isEqualTo("HOLIDAY");
		assertThat(response.reason()).isEqualTo("Republic Day");
	}

	@Test
	void holidayOutsideWorkingHoursReturnsHoliday() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setKolkataTime(18, 0);
		holidays.save(new Holiday(kolkataBranch, LocalDate.of(2026, 1, 15), "Republic Day"));

		var response = statusOf(kolkataBranch);

		assertThat(response.state()).isEqualTo("HOLIDAY");
		assertThat(response.reason()).isEqualTo("Republic Day");
	}

	@Test
	void inactiveBranchTakesPrecedenceOverHoliday() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setKolkataTime(10, 0);
		kolkataBranch.setStatus(BranchStatus.INACTIVE);
		branches.save(kolkataBranch);
		holidays.save(new Holiday(kolkataBranch, LocalDate.of(2026, 1, 15), "Republic Day"));

		var response = statusOf(kolkataBranch);

		assertThat(response.state()).isEqualTo("INACTIVE");
	}

	@Test
	void beforeOpeningReturnsClosed() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setKolkataTime(8, 0);

		assertThat(statusOf(kolkataBranch).state()).isEqualTo("CLOSED");
	}

	@Test
	void atOpeningReturnsOpen() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setKolkataTime(9, 0);

		assertThat(statusOf(kolkataBranch).state()).isEqualTo("OPEN");
	}

	@Test
	void beforeClosingReturnsOpen() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setKolkataTime(16, 59);

		assertThat(statusOf(kolkataBranch).state()).isEqualTo("OPEN");
	}

	@Test
	void atClosingReturnsClosed() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setKolkataTime(17, 0);

		assertThat(statusOf(kolkataBranch).state()).isEqualTo("CLOSED");
	}

	@Test
	void splitIntervalAtNoonReturnsOpen() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(13, 0));
		addSplitAfternoon(LocalTime.of(14, 0), LocalTime.of(18, 0));
		setKolkataTime(12, 0);

		assertThat(statusOf(kolkataBranch).state()).isEqualTo("OPEN");
	}

	@Test
	void splitIntervalAtFirstCloseReturnsClosed() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(13, 0));
		addSplitAfternoon(LocalTime.of(14, 0), LocalTime.of(18, 0));
		setKolkataTime(13, 0);

		assertThat(statusOf(kolkataBranch).state()).isEqualTo("CLOSED");
	}

	@Test
	void splitIntervalInGapReturnsClosed() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(13, 0));
		addSplitAfternoon(LocalTime.of(14, 0), LocalTime.of(18, 0));
		setKolkataTime(13, 30);

		assertThat(statusOf(kolkataBranch).state()).isEqualTo("CLOSED");
	}

	@Test
	void splitIntervalAtAfternoonOpenReturnsOpen() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(13, 0));
		addSplitAfternoon(LocalTime.of(14, 0), LocalTime.of(18, 0));
		setKolkataTime(14, 0);

		assertThat(statusOf(kolkataBranch).state()).isEqualTo("OPEN");
	}

	@Test
	void splitIntervalAtAfternoonCloseReturnsClosed() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(13, 0));
		addSplitAfternoon(LocalTime.of(14, 0), LocalTime.of(18, 0));
		setKolkataTime(18, 0);

		assertThat(statusOf(kolkataBranch).state()).isEqualTo("CLOSED");
	}

	@Test
	void noWorkingIntervalForWeekdayReturnsClosed() {
		setKolkataTime(10, 0);

		var response = statusOf(kolkataBranch);

		assertThat(response.state()).isEqualTo("CLOSED");
		assertThat(response.reason()).isEqualTo("Outside working hours");
	}

	@Test
	void kolkataBranchUsesKolkataTimezone() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setKolkataTime(10, 0);

		var response = statusOf(kolkataBranch);

		assertThat(response.timezone()).isEqualTo("Asia/Kolkata");
		assertThat(response.state()).isEqualTo("OPEN");
	}

	@Test
	void newYorkBranchUsesNewYorkTimezone() {
		setFullWeek(newYorkBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setNewYorkTime(10, 0);

		var response = statusOf(newYorkBranch);

		assertThat(response.timezone()).isEqualTo("America/New_York");
		assertThat(response.state()).isEqualTo("OPEN");
	}

	@Test
	void tokyoBranchUsesTokyoTimezone() {
		setFullWeek(tokyoBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setTokyoTime(10, 0);

		var response = statusOf(tokyoBranch);

		assertThat(response.timezone()).isEqualTo("Asia/Tokyo");
		assertThat(response.state()).isEqualTo("OPEN");
	}

	@Test
	void sameInstantDifferentTimezoneProducesDifferentLocalTime() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setFullWeek(newYorkBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));

		// 14:30Z on 2026-01-15 (a Thursday, so both branches have
		// working intervals) is 20:00 in Kolkata (IST, UTC+5:30 —
		// after the 17:00 close) and 09:30 in New York (EST, UTC-5 —
		// inside [09:00, 17:00)). The old expectations were inverted.
		clock.set(Instant.parse("2026-01-15T14:30:00Z"));

		assertThat(statusOf(kolkataBranch).state()).isEqualTo("CLOSED");
		assertThat(statusOf(newYorkBranch).state()).isEqualTo("OPEN");
	}

	@Test
	void responseNowIsBasedOnBackendInstant() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		Instant fixed = Instant.parse("2026-01-15T10:30:00Z");
		clock.set(fixed);

		var response = statusOf(kolkataBranch);

		assertThat(response.now()).isEqualTo(fixed);
	}

	@Test
	void todaysHolidayIsHolidayNotUpcoming() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setKolkataTime(10, 0);
		holidays.save(new Holiday(kolkataBranch, LocalDate.of(2026, 1, 15), "Today"));
		holidays.save(new Holiday(kolkataBranch, LocalDate.of(2026, 12, 25), "Christmas"));

		var response = statusOf(kolkataBranch);

		assertThat(response.state()).isEqualTo("HOLIDAY");
		assertThat(response.upcomingHoliday()).isNotNull();
		assertThat(response.upcomingHoliday().name()).isEqualTo("Christmas");
		assertThat(response.upcomingHoliday().date()).isEqualTo(LocalDate.of(2026, 12, 25));
	}

	@Test
	void futureHolidayReturnedAsUpcoming() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setKolkataTime(10, 0);
		holidays.save(new Holiday(kolkataBranch, LocalDate.of(2026, 6, 15), "Summer"));
		holidays.save(new Holiday(kolkataBranch, LocalDate.of(2026, 12, 25), "Christmas"));

		var response = statusOf(kolkataBranch);

		assertThat(response.state()).isEqualTo("OPEN");
		assertThat(response.upcomingHoliday()).isNotNull();
		assertThat(response.upcomingHoliday().name()).isEqualTo("Summer");
	}

	@Test
	void pastHolidayNotReturnedAsUpcoming() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setKolkataTime(10, 0);
		holidays.save(new Holiday(kolkataBranch, LocalDate.of(2026, 1, 1), "New Year"));

		var response = statusOf(kolkataBranch);

		assertThat(response.state()).isEqualTo("OPEN");
		assertThat(response.upcomingHoliday()).isNull();
	}

	@Test
	void noHolidayAtAllReturnsNullUpcoming() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setKolkataTime(10, 0);

		var response = statusOf(kolkataBranch);

		assertThat(response.state()).isEqualTo("OPEN");
		assertThat(response.upcomingHoliday()).isNull();
	}

	@Test
	void responseContainsAllExpectedFields() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setKolkataTime(10, 0);

		var response = statusOf(kolkataBranch);

		assertThat(response.branchId()).isEqualTo(kolkataBranch.getId());
		assertThat(response.timezone()).isEqualTo("Asia/Kolkata");
		assertThat(response.now()).isNotNull();
		assertThat(response.state()).isEqualTo("OPEN");
		assertThat(response.todayIntervals()).hasSize(1);
		assertThat(response.todayIntervals().get(0).opensAt()).isEqualTo(LocalTime.of(9, 0));
		assertThat(response.todayIntervals().get(0).closesAt()).isEqualTo(LocalTime.of(17, 0));
		assertThat(response.upcomingHoliday()).isNull();
		assertThat(response.reason()).isNull();
	}

	@Test
	void closedStateReturnsReason() {
		setFullWeek(kolkataBranch, LocalTime.of(9, 0), LocalTime.of(17, 0));
		setKolkataTime(18, 0);

		var response = statusOf(kolkataBranch);

		assertThat(response.state()).isEqualTo("CLOSED");
		assertThat(response.reason()).isEqualTo("Outside working hours");
	}
}
