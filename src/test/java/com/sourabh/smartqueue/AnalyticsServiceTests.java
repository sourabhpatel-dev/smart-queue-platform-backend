package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.analytics.AnalyticsScopeMismatchException;
import com.sourabh.smartqueue.analytics.api.AnalyticsOverviewResponse;
import com.sourabh.smartqueue.analytics.application.AnalyticsService;
import com.sourabh.smartqueue.appointment.Appointment;
import com.sourabh.smartqueue.appointment.AppointmentRepository;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueEntry;
import com.sourabh.smartqueue.queue.QueueEntryRepository;
import com.sourabh.smartqueue.queue.QueuePriority;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.ServiceSession;
import com.sourabh.smartqueue.queue.ServiceSessionRepository;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Operational analytics aggregation behavior (see ADR-035).
 *
 * <p>Every metric is seeded with explicit instants and asserted
 * exactly: status splits, window boundaries, branch/service filters,
 * timezone conversion (including DST), running-vs-ended sessions,
 * wait-time membership, zero-data nulls, and tenant isolation. Each
 * test rolls back. Queue-entry timestamps are backdated through JDBC
 * after {@code saveAndFlush} (the entity stamps wall-clock time at
 * construction, deliberately outside Clock control — the retention
 * suite's established seeding lesson).
 */
@Testcontainers
@SpringBootTest
@Transactional
class AnalyticsServiceTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

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
	ServiceCounterRepository counters;
	@Autowired
	UserRepository users;

	@Autowired
	OrganizationMembershipRepository memberships;
	@Autowired
	QueueRepository queues;
	@Autowired
	QueueEntryRepository entries;
	@Autowired
	ServiceSessionRepository sessions;
	@Autowired
	AppointmentRepository appointments;
	@Autowired
	AnalyticsService analytics;
	@Autowired
	JdbcTemplate jdbc;

	Organization orgA;
	Organization orgB;
	Branch downtown;
	Branch uptown;
	com.sourabh.smartqueue.service.Service consultation;
	com.sourabh.smartqueue.service.Service dental;
	User customerA;
	User managerA;
	User staffA;
	User customerPlain;
	int token = 0;
	int entryCustomers = 0;
	String tag;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		tag = UUID.randomUUID().toString().substring(0, 8);
		orgA = organizations.save(new Organization("Analytics A " + tag));
		orgB = organizations.save(new Organization("Analytics B " + tag));
		downtown = branches.save(new Branch(orgA, "Downtown " + tag, "Bhopal", "India", KOLKATA));
		uptown = branches.save(new Branch(orgA, "Uptown " + tag, "New York", "USA", NEW_YORK));
		consultation = services.save(new com.sourabh.smartqueue.service.Service(
				downtown, "Consultation " + tag, Duration.ofMinutes(30)));
		dental = services.save(new com.sourabh.smartqueue.service.Service(
				downtown, "Dental " + tag, Duration.ofMinutes(45)));
		customerA = user(orgA, "cust-" + tag, RoleType.CUSTOMER);
		managerA = user(orgA, "mgr-" + tag, RoleType.MANAGER);
		staffA = user(orgA, "staff-" + tag, RoleType.STAFF);
		customerPlain = user(orgA, "plain-" + tag, RoleType.CUSTOMER);
	}

	private User user(Organization org, String local, RoleType role) {
		User user = users.save(new User(org, local + "@example.com", local));
		user.grantRole(role);
		User saved = users.save(user);
		// Mirrors the V20 backfill for fixtures (ADR-040): operational
		// roles carry the ACTIVE membership the policy authorizes on.
		if (role != RoleType.CUSTOMER) {
			memberships.save(new OrganizationMembership(saved, org,
					MembershipRole.valueOf(role.name())));
		}
		return saved;
	}

	private AuthenticatedPrincipal principal(User user, RoleType... roles) {
		return new AuthenticatedPrincipal(user.getId(), user.getOrganization().getId(),
				user.getEmail(), Set.of(roles));
	}

	private Appointment appointment(Branch branch,
			com.sourabh.smartqueue.service.Service service, Instant start) {
		return appointments.saveAndFlush(new Appointment(branch.getOrganization(), branch,
				service, customerA, start, start.plus(Duration.ofMinutes(30))));
	}

	private Queue queue(Branch branch, com.sourabh.smartqueue.service.Service service) {
		return queues.saveAndFlush(new Queue(branch.getOrganization(), branch, service));
	}

	/**
	 * Drives an entry to the requested terminal path, then pins all
	 * three lifecycle stamps to explicit instants. {@code sessionStart}
	 * null means no session row is created (entry never reached
	 * service); {@code sessionEnd} null means the session stays
	 * running.
	 *
	 * <p>Each entry is created for a freshly minted customer: the
	 * {@code uq_active_entry} partial unique index allows only one
	 * WAITING/CALLED/SERVING row per (queue, customer), so reusing a
	 * single customer across entries in one test would collide on the
	 * second active insert. No assertion in this suite depends on
	 * entries sharing a customer.
	 */
	private QueueEntry entry(Queue queue, QueueEntryStatusTarget target,
			Instant joinedAt, Instant sessionStart, Instant sessionEnd) {
		User entryCustomer = users.save(new User(orgA,
				"entry-" + (++entryCustomers) + "-" + tag + "@example.com",
				"Entry " + entryCustomers));
		entryCustomer.grantRole(RoleType.CUSTOMER);
		entryCustomer = users.save(entryCustomer);
		QueueEntry entry = entries.saveAndFlush(new QueueEntry(queue.getOrganization(),
				queue, entryCustomer, ++token, LocalDate.of(2026, 10, 7), QueuePriority.NORMAL));
		if (target.ordinal() >= QueueEntryStatusTarget.CALLED.ordinal()) {
			entry.call();
		}
		if (target.ordinal() >= QueueEntryStatusTarget.SERVING.ordinal()) {
			entry.startServing();
		}
		switch (target) {
			case COMPLETED -> entry.complete();
			case CANCELLED -> entry.cancel();
			case NO_SHOW -> entry.markNoShow();
			default -> {
			}
		}
		entries.saveAndFlush(entry);
		jdbc.update("UPDATE queue_entry SET joined_at = ? WHERE id = ?",
				Timestamp.from(joinedAt), entry.getId());
		if (sessionStart != null) {
			ServiceCounter counter = counters.saveAndFlush(new ServiceCounter(
					queue.getBranch(), "Counter " + UUID.randomUUID().toString().substring(0, 4)));
			ServiceSession session = new ServiceSession(queue.getBranch(), entry,
					counter, queue.getService(), sessionStart);
			if (sessionEnd != null) {
				session.end(sessionEnd);
			}
			sessions.saveAndFlush(session);
		}
		return entry;
	}

	private enum QueueEntryStatusTarget {
		WAITING, CALLED, SERVING, COMPLETED, CANCELLED, NO_SHOW
	}

	private AnalyticsOverviewResponse report(LocalDateTime from, LocalDateTime to,
			String timezone, UUID branchId, UUID serviceId) {
		return analytics.overview(principal(managerA, RoleType.MANAGER),
				from, to, timezone, branchId, serviceId);
	}

	@Test
	void fullMetricsAreAggregatedExactly() {
		Instant base = Instant.parse("2026-10-07T10:00:00Z");
		appointment(downtown, consultation, base);
		// No flow in the current schema ever stores CONFIRMED (booking
		// creates SCHEDULED; no confirm endpoint exists), so the
		// second SCHEDULED row documents the honest zero below.
		appointment(downtown, consultation, base.plus(Duration.ofHours(1)));
		Appointment done = appointment(downtown, consultation, base.plus(Duration.ofHours(2)));
		done.complete();
		appointments.saveAndFlush(done);
		Appointment off = appointment(downtown, consultation, base.plus(Duration.ofHours(3)));
		off.cancel();
		appointments.saveAndFlush(off);
		Appointment ns = appointment(downtown, consultation, base.plus(Duration.ofHours(4)));
		ns.markNoShow();
		appointments.saveAndFlush(ns);

		Queue queue = queue(downtown, consultation);
		// 20-minute wait, 30-minute service.
		entry(queue, QueueEntryStatusTarget.COMPLETED, base,
				base.plus(Duration.ofMinutes(20)), base.plus(Duration.ofMinutes(50)));
		// 40-minute wait, 15-minute service.
		entry(queue, QueueEntryStatusTarget.COMPLETED, base.plus(Duration.ofMinutes(5)),
				base.plus(Duration.ofMinutes(45)), base.plus(Duration.ofHours(1)));
		entry(queue, QueueEntryStatusTarget.WAITING, base.plus(Duration.ofMinutes(10)),
				null, null);

		AnalyticsOverviewResponse response = report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				"UTC", null, null);

		assertThat(response.timezone()).isEqualTo("UTC");
		assertThat(response.from()).isEqualTo(Instant.parse("2026-10-07T00:00:00Z"));
		assertThat(response.to()).isEqualTo(Instant.parse("2026-10-08T00:00:00Z"));
		// 2 SCHEDULED + completed/cancelled/no-show (CONFIRMED stays
		// 0 — no current flow stores it).
		assertThat(response.appointments().total()).isEqualTo(5);
		assertThat(response.appointments().scheduled()).isEqualTo(2);
		assertThat(response.appointments().confirmed()).isEqualTo(0);
		assertThat(response.appointments().completed()).isEqualTo(1);
		assertThat(response.appointments().cancelled()).isEqualTo(1);
		assertThat(response.appointments().noShow()).isEqualTo(1);

		assertThat(response.queue().joined()).isEqualTo(3);
		assertThat(response.queue().completed()).isEqualTo(2);
		assertThat(response.queue().waiting()).isEqualTo(1);
		assertThat(response.queue().averageWaitMinutes()).isEqualTo(30.0);

		assertThat(response.sessions().total()).isEqualTo(2);
		assertThat(response.sessions().completed()).isEqualTo(2);
		assertThat(response.sessions().averageDurationMinutes()).isEqualTo(22.5);
		assertThat(response.sessions().totalDurationMinutes()).isEqualTo(45.0);

		assertThat(response.serviceBreakdown()).hasSize(1);
		AnalyticsOverviewResponse.ServiceBreakdownRow service = response.serviceBreakdown().get(0);
		assertThat(service.serviceId()).isEqualTo(consultation.getId());
		assertThat(service.serviceName()).isEqualTo(consultation.getName());
		assertThat(service.appointmentCount()).isEqualTo(5);
		assertThat(service.completedAppointmentCount()).isEqualTo(1);
		assertThat(service.queueEntryCount()).isEqualTo(3);
		assertThat(service.completedSessionCount()).isEqualTo(2);
		assertThat(service.averageServiceDurationMinutes()).isEqualTo(22.5);

		assertThat(response.branchBreakdown()).hasSize(1);
		AnalyticsOverviewResponse.BranchBreakdownRow branch = response.branchBreakdown().get(0);
		assertThat(branch.branchId()).isEqualTo(downtown.getId());
		assertThat(branch.appointmentCount()).isEqualTo(5);
		assertThat(branch.completedAppointmentCount()).isEqualTo(1);
		assertThat(branch.queueEntryCount()).isEqualTo(3);
		assertThat(branch.completedSessionCount()).isEqualTo(2);
	}

	@Test
	void windowBoundariesAreFromInclusiveToExclusive() {
		Instant from = Instant.parse("2026-10-07T00:00:00Z");
		Instant to = Instant.parse("2026-10-08T00:00:00Z");
		appointment(downtown, consultation, from);
		appointment(downtown, consultation, to);
		Queue queue = queue(downtown, consultation);
		entry(queue, QueueEntryStatusTarget.WAITING, from, null, null);
		entry(queue, QueueEntryStatusTarget.WAITING, to, null, null);

		AnalyticsOverviewResponse response = report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				"UTC", null, null);

		assertThat(response.appointments().total()).isEqualTo(1);
		assertThat(response.queue().joined()).isEqualTo(1);
	}

	@Test
	void everyAggregateSharesOneEffectiveWindow() {
		// Rows just outside either edge must vanish from ALL sections
		// at once — summary and breakdowns filter identically.
		Instant from = Instant.parse("2026-10-07T00:00:00Z");
		Instant to = Instant.parse("2026-10-08T00:00:00Z");
		appointment(downtown, consultation, from.minusSeconds(1));
		appointment(downtown, consultation, to);
		Queue queue = queue(downtown, consultation);
		// All three anchors (startAt, joinedAt, startedAt) sit outside
		// the window: a session that merely ends inside it must not
		// appear, because sessions anchor on startedAt.
		entry(queue, QueueEntryStatusTarget.COMPLETED, from.minus(Duration.ofHours(1)),
				from.minus(Duration.ofMinutes(30)), from.minus(Duration.ofMinutes(10)));

		AnalyticsOverviewResponse response = report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				"UTC", null, null);

		assertThat(response.appointments().total()).isEqualTo(0);
		assertThat(response.queue().joined()).isEqualTo(0);
		assertThat(response.queue().averageWaitMinutes()).isNull();
		assertThat(response.sessions().total()).isEqualTo(0);
		assertThat(response.serviceBreakdown()).isEmpty();
		assertThat(response.branchBreakdown()).isEmpty();
	}

	@Test
	void branchScopeDerivesBranchTimezone() {
		// 10:00 IST on Oct 7 == 04:30Z. The local window
		// [Oct 7 00:00, Oct 8 00:00) IST is [Oct 6 18:30Z, Oct 7 18:30Z).
		Instant start = Instant.parse("2026-10-07T04:30:00Z");
		appointment(downtown, consultation, start);

		AnalyticsOverviewResponse response = report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				null, downtown.getId(), null);

		assertThat(response.timezone()).isEqualTo("Asia/Kolkata");
		assertThat(response.from()).isEqualTo(Instant.parse("2026-10-06T18:30:00Z"));
		assertThat(response.to()).isEqualTo(Instant.parse("2026-10-07T18:30:00Z"));
		assertThat(response.appointments().total()).isEqualTo(1);
		assertThat(response.branchId()).isEqualTo(downtown.getId());
		assertThat(response.branchBreakdown()).hasSize(1);
	}

	@Test
	void explicitTimezoneWinsOverBranchZone() {
		Instant start = Instant.parse("2026-10-07T04:30:00Z");
		appointment(downtown, consultation, start);

		AnalyticsOverviewResponse response = report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				"UTC", downtown.getId(), null);

		assertThat(response.timezone()).isEqualTo("UTC");
		assertThat(response.appointments().total()).isEqualTo(1);
	}

	@Test
	void fallBackDaySpansTwentyFiveHours() {
		// DST ends in America/New_York on 2026-11-01: the local day
		// [Nov 1 00:00, Nov 2 00:00) runs 25 hours
		// (04:00Z Nov 1 → 05:00Z... precisely 2026-11-01T04:00Z to
		// 2026-11-02T05:00Z? No: Nov 2 00:00 EST == 05:00Z).
		Queue queue = queue(uptown, consultationFor(uptown));
		Instant lastIncluded = Instant.parse("2026-11-02T04:59:59Z");
		Instant firstExcluded = Instant.parse("2026-11-02T05:00:00Z");
		entry(queue, QueueEntryStatusTarget.WAITING, lastIncluded, null, null);
		entry(queue, QueueEntryStatusTarget.WAITING, firstExcluded, null, null);

		AnalyticsOverviewResponse response = report(
				LocalDateTime.parse("2026-11-01T00:00:00"),
				LocalDateTime.parse("2026-11-02T00:00:00"),
				"America/New_York", null, null);

		assertThat(response.from()).isEqualTo(Instant.parse("2026-11-01T04:00:00Z"));
		assertThat(response.to()).isEqualTo(Instant.parse("2026-11-02T05:00:00Z"));
		assertThat(response.queue().joined()).isEqualTo(1);
	}

	@Test
	void springForwardGapShiftsForward() {
		// 02:30 does not exist in America/New_York on 2026-03-08
		// (02:00 EST → 03:00 EDT); java.time resolves the gap by
		// shifting forward to 03:30 EDT == 07:30Z.
		AnalyticsOverviewResponse response = report(
				LocalDateTime.parse("2026-03-08T02:30:00"),
				LocalDateTime.parse("2026-03-08T04:00:00"),
				"America/New_York", null, null);

		assertThat(response.from()).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
		assertThat(response.to()).isEqualTo(Instant.parse("2026-03-08T08:00:00Z"));
		assertThat(response.appointments().total()).isEqualTo(0);
		assertThat(response.queue().averageWaitMinutes()).isNull();
	}

	@Test
	void filtersCombineBranchAndService() {
		Instant base = Instant.parse("2026-10-07T10:00:00Z");
		appointment(downtown, consultation, base);
		appointment(downtown, dental, base.plus(Duration.ofHours(1)));
		appointment(uptown, consultationFor(uptown), base.plus(Duration.ofHours(2)));

		AnalyticsOverviewResponse branchOnly = report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				"UTC", downtown.getId(), null);
		assertThat(branchOnly.appointments().total()).isEqualTo(2);
		assertThat(branchOnly.serviceBreakdown())
				.extracting(AnalyticsOverviewResponse.ServiceBreakdownRow::serviceName)
				.containsExactlyInAnyOrder(consultation.getName(), dental.getName());

		AnalyticsOverviewResponse serviceOnly = report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				"UTC", null, dental.getId());
		assertThat(serviceOnly.appointments().total()).isEqualTo(1);
		assertThat(serviceOnly.serviceBreakdown()).hasSize(1);
		assertThat(serviceOnly.serviceBreakdown().get(0).serviceId())
				.isEqualTo(dental.getId());

		AnalyticsOverviewResponse combined = report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				"UTC", downtown.getId(), dental.getId());
		assertThat(combined.appointments().total()).isEqualTo(1);
		assertThat(combined.serviceBreakdown()).hasSize(1);
		assertThat(combined.branchBreakdown()).hasSize(1);
		assertThat(combined.branchBreakdown().get(0).branchId())
				.isEqualTo(downtown.getId());
	}

	private com.sourabh.smartqueue.service.Service consultationFor(Branch branch) {
		if (branch.getId().equals(downtown.getId())) {
			return consultation;
		}
		return services.save(new com.sourabh.smartqueue.service.Service(branch,
				"Consultation " + UUID.randomUUID().toString().substring(0, 8),
				Duration.ofMinutes(30)));
	}

	@Test
	void sameOrgServiceOutsideBranchIs422() {
		assertThatThrownBy(() -> report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				"UTC", uptown.getId(), consultation.getId()))
				.isInstanceOf(AnalyticsScopeMismatchException.class);
	}

	@Test
	void foreignBranchAndServiceAreScopeSafe404() {
		Branch foreignBranch = branches.save(
				new Branch(orgB, "Elsewhere", "Pune", "India", KOLKATA));
		com.sourabh.smartqueue.service.Service foreignService = services.save(
				new com.sourabh.smartqueue.service.Service(foreignBranch, "Foreign",
						Duration.ofMinutes(30)));

		assertThatThrownBy(() -> report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				"UTC", foreignBranch.getId(), null))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("Branch not found");
		assertThatThrownBy(() -> report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				"UTC", null, foreignService.getId()))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("Service not found");
		assertThatThrownBy(() -> report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				"UTC", UUID.randomUUID(), null))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void otherOrganizationsDataStaysInvisible() {
		Instant base = Instant.parse("2026-10-07T10:00:00Z");
		Branch foreignBranch = branches.save(
				new Branch(orgB, "Elsewhere", "Pune", "India", KOLKATA));
		com.sourabh.smartqueue.service.Service foreignService = services.save(
				new com.sourabh.smartqueue.service.Service(foreignBranch, "Foreign",
						Duration.ofMinutes(30)));
		User foreignCustomer = user(orgB, "foreign", RoleType.CUSTOMER);
		appointments.saveAndFlush(new Appointment(orgB, foreignBranch, foreignService,
				foreignCustomer, base, base.plus(Duration.ofMinutes(30))));
		appointment(downtown, consultation, base);

		AnalyticsOverviewResponse response = report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				"UTC", null, null);

		assertThat(response.appointments().total()).isEqualTo(1);
		assertThat(response.branchBreakdown()).hasSize(1);
		assertThat(response.branchBreakdown().get(0).branchId())
				.isEqualTo(downtown.getId());
	}

	@Test
	void staffAndCustomersAreDenied() {
		AuthenticatedPrincipal staff = principal(staffA, RoleType.STAFF);
		AuthenticatedPrincipal customer = principal(customerPlain, RoleType.CUSTOMER);

		assertThatThrownBy(() -> analytics.overview(staff,
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"), "UTC", null, null))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> analytics.overview(customer,
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"), "UTC", null, null))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void invalidRangesAreRejectedNeverTruncated() {
		LocalDateTime from = LocalDateTime.parse("2026-10-08T00:00:00");
		LocalDateTime to = LocalDateTime.parse("2026-10-07T00:00:00");

		// Note the deliberately confusing locals: `from` is the later
		// instant, so the inverted range is report(from, to) — the old
		// call passed (to, from), which is a valid [Oct 7, Oct 8)
		// window and never threw.
		assertThatThrownBy(() -> report(from, to, "UTC", null, null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("from < to");
		assertThatThrownBy(() -> report(from, from, "UTC", null, null))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> report(LocalDateTime.parse("2025-01-01T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"), "UTC", null, null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("366");
		assertThatThrownBy(() -> report(from, to.plusDays(1), "no-such-zone", null, null))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> report(from, to.plusDays(1), null, null, null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("timezone is required");
	}

	@Test
	void zeroDataMeansZeroCountsNullAveragesEmptyBreakdowns() {
		AnalyticsOverviewResponse response = report(
				LocalDateTime.parse("2030-01-01T00:00:00"),
				LocalDateTime.parse("2030-01-08T00:00:00"),
				"UTC", null, null);

		assertThat(response.appointments().total()).isEqualTo(0);
		assertThat(response.appointments().completed()).isEqualTo(0);
		assertThat(response.queue().joined()).isEqualTo(0);
		assertThat(response.queue().averageWaitMinutes()).isNull();
		assertThat(response.sessions().total()).isEqualTo(0);
		assertThat(response.sessions().completed()).isEqualTo(0);
		assertThat(response.sessions().averageDurationMinutes()).isNull();
		assertThat(response.sessions().totalDurationMinutes()).isNull();
		assertThat(response.serviceBreakdown()).isEmpty();
		assertThat(response.branchBreakdown()).isEmpty();
	}

	@Test
	void runningSessionsCountWithoutDuration() {
		Instant base = Instant.parse("2026-10-07T10:00:00Z");
		Queue queue = queue(downtown, consultation);
		entry(queue, QueueEntryStatusTarget.SERVING, base,
				base.plus(Duration.ofMinutes(10)), null);

		AnalyticsOverviewResponse response = report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				"UTC", null, null);

		assertThat(response.sessions().total()).isEqualTo(1);
		assertThat(response.sessions().completed()).isEqualTo(0);
		assertThat(response.sessions().averageDurationMinutes()).isNull();
		assertThat(response.sessions().totalDurationMinutes()).isNull();
		// SERVING entries are not completed visits: no wait average.
		assertThat(response.queue().serving()).isEqualTo(1);
		assertThat(response.queue().averageWaitMinutes()).isNull();
	}

	@Test
	void completedEntryWithoutSessionCountsButContributesNoWait() {
		Instant base = Instant.parse("2026-10-07T10:00:00Z");
		Queue queue = queue(downtown, consultation);
		entry(queue, QueueEntryStatusTarget.COMPLETED, base, null, null);
		entry(queue, QueueEntryStatusTarget.COMPLETED, base.plus(Duration.ofMinutes(5)),
				base.plus(Duration.ofMinutes(25)), base.plus(Duration.ofMinutes(40)));

		AnalyticsOverviewResponse response = report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				"UTC", null, null);

		// Both completions count; only the session-backed one (20-minute
		// wait) feeds the average — the inner join is the definition.
		assertThat(response.queue().completed()).isEqualTo(2);
		assertThat(response.queue().averageWaitMinutes()).isEqualTo(20.0);
	}

	@Test
	void historicalWaitDerivesFromStoredTimestampsNotEstimates() {
		// The Phase 2I estimator is a live, forward-looking snapshot
		// (position × duration) with no persisted row — it cannot answer
		// "how long did customers wait". This average comes only from
		// joinedAt and session startedAt, hand-computed below.
		Instant joined = Instant.parse("2026-10-07T09:00:00Z");
		Instant served = Instant.parse("2026-10-07T09:37:00Z");
		Queue queue = queue(downtown, consultation);
		entry(queue, QueueEntryStatusTarget.COMPLETED, joined,
				served, served.plus(Duration.ofMinutes(20)));

		AnalyticsOverviewResponse response = report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				"UTC", null, null);

		assertThat(response.queue().averageWaitMinutes()).isEqualTo(37.0);
	}

	@Test
	void breakdownOrdersByVolumeThenName() {
		Instant base = Instant.parse("2026-10-07T10:00:00Z");
		appointment(downtown, dental, base);
		appointment(downtown, dental, base.plus(Duration.ofHours(1)));
		appointment(downtown, consultation, base.plus(Duration.ofHours(2)));
		Queue dentalQueue = queue(downtown, dental);
		entry(dentalQueue, QueueEntryStatusTarget.WAITING, base, null, null);

		AnalyticsOverviewResponse response = report(
				LocalDateTime.parse("2026-10-07T00:00:00"),
				LocalDateTime.parse("2026-10-08T00:00:00"),
				"UTC", null, null);

		// Dental volume 3 (2 appointments + 1 entry) precedes
		// consultation volume 1 — deterministic, no invented score.
		assertThat(response.serviceBreakdown())
				.extracting(AnalyticsOverviewResponse.ServiceBreakdownRow::serviceName)
				.containsExactly(dental.getName(), consultation.getName());
	}
}




