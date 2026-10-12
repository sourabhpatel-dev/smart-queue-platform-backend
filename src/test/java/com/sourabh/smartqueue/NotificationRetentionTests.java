package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.notification.Notification;
import com.sourabh.smartqueue.notification.NotificationReferenceType;
import com.sourabh.smartqueue.notification.NotificationRepository;
import com.sourabh.smartqueue.notification.NotificationRetentionProperties;
import com.sourabh.smartqueue.notification.NotificationType;
import com.sourabh.smartqueue.notification.application.NotificationRetentionService;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Notification retention behavior (see ADR-033): eligibility anchored
 * on {@code createdAt} with strict cutoffs, read/unread policies
 * applied independently, tenant data untouched, bounded id batches,
 * and scheduler failure containment.
 *
 * <p>Time is pinned: a fixed project Clock defines "now" and rows are
 * backdated through JDBC (the entity lifecycle stamps wall-clock time,
 * deliberately outside Clock control). Every test rolls back — the
 * retention sweep never leaks rows between tests.
 */
@Testcontainers
@SpringBootTest
@Transactional
class NotificationRetentionTests {

	static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@TestConfiguration
	static class FixedClockConfiguration {
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	@Autowired
	OrganizationRepository organizations;
	@Autowired
	UserRepository users;
	@Autowired
	NotificationRepository notifications;
	@Autowired
	NotificationRetentionService retention;
	@Autowired
	NotificationRetentionProperties policy;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	PlatformTransactionManager transactionManager;
	@Autowired
	Clock clock;

	Organization orgA;
	Organization orgB;
	User customerA;
	User customerB;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		orgA = organizations.save(new Organization("Retention A " + tag));
		orgB = organizations.save(new Organization("Retention B " + tag));
		customerA = customer(orgA, "ra-" + tag);
		customerB = customer(orgB, "rb-" + tag);
		assertThat(clock.instant()).isEqualTo(NOW);
	}

	private User customer(Organization org, String local) {
		User user = users.save(new User(org, local + "@example.com", local));
		user.grantRole(RoleType.CUSTOMER);
		return users.save(user);
	}

	private Notification seed(User recipient, boolean read, Instant createdAt) {
		// saveAndFlush first: the JDBC backdate below must hit a committed
		// (within this test transaction) row — a bare save() leaves the
		// insert pending in the persistence context and the UPDATE would
		// silently affect zero rows.
		Notification notification = notifications.saveAndFlush(new Notification(
				recipient.getOrganization(), recipient, NotificationType.QUEUE_JOINED,
				"Title", "Message " + UUID.randomUUID(),
				NotificationReferenceType.QUEUE_ENTRY, UUID.randomUUID(), UUID.randomUUID()));
		int updated = jdbc.update("UPDATE notification SET created_at = ?, read_at = ? WHERE id = ?",
				Timestamp.from(createdAt),
				read ? Timestamp.from(createdAt.plusSeconds(60)) : null,
				notification.getId());
		assertThat(updated).isEqualTo(1);
		return notification;
	}

	private Instant daysAgo(long days) {
		return NOW.minus(java.time.Duration.ofDays(days));
	}

	/**
	 * Database-level existence: the purge deletes with bulk statements
	 * that bypass the test's persistence context, so a managed
	 * {@code findById} would keep returning the stale instance. The row
	 * count is the honest assertion — the database is authoritative.
	 */
	private boolean existsInDb(UUID id) {
		Long count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM notification WHERE id = ?", Long.class, id);
		return count != null && count > 0;
	}

	@Test
	void oldReadIsDeletedWhileRecentAndBoundaryReadAreKept() {
		Notification old = seed(customerA, true, daysAgo(100));
		Notification recent = seed(customerA, true, daysAgo(10));
		Notification boundary = seed(customerA, true, NOW.minus(policy.getReadAfter()));

		NotificationRetentionService.PurgeSummary summary = retention.purgeOnce();

		assertThat(summary.deletedRead()).isEqualTo(1);
		assertThat(summary.deletedUnread()).isEqualTo(0);
		assertThat(existsInDb(old.getId())).isFalse();
		assertThat(existsInDb(recent.getId())).isTrue();
		assertThat(existsInDb(boundary.getId())).isTrue();
	}

	@Test
	void oldUnreadIsDeletedWhileRecentAndBoundaryUnreadAreKept() {
		Notification old = seed(customerA, false, daysAgo(400));
		Notification recent = seed(customerA, false, daysAgo(10));
		Notification boundary = seed(customerA, false, NOW.minus(policy.getUnreadAfter()));

		NotificationRetentionService.PurgeSummary summary = retention.purgeOnce();

		assertThat(summary.deletedRead()).isEqualTo(0);
		assertThat(summary.deletedUnread()).isEqualTo(1);
		assertThat(existsInDb(old.getId())).isFalse();
		assertThat(existsInDb(recent.getId())).isTrue();
		assertThat(existsInDb(boundary.getId())).isTrue();
	}

	@Test
	void policiesApplyIndependentlyAndOtherTenantsStayUntouched() {
		Notification oldReadA = seed(customerA, true, daysAgo(100));
		Notification oldUnreadA = seed(customerA, false, daysAgo(400));
		Notification recentB = seed(customerB, false, daysAgo(10));
		Notification oldReadB = seed(customerB, true, daysAgo(200));

		NotificationRetentionService.PurgeSummary summary = retention.purgeOnce();

		assertThat(summary.deletedRead()).isEqualTo(2);
		assertThat(summary.deletedUnread()).isEqualTo(1);
		assertThat(existsInDb(oldReadA.getId())).isFalse();
		assertThat(existsInDb(oldUnreadA.getId())).isFalse();
		assertThat(existsInDb(oldReadB.getId())).isFalse();
		Notification survivor = notifications.findById(recentB.getId()).orElseThrow();
		assertThat(survivor.getOrganization().getId()).isEqualTo(orgB.getId());
		assertThat(survivor.getRecipientUser().getId()).isEqualTo(customerB.getId());
		assertThat(survivor.getTitle()).isNotBlank();
	}

	@Test
	void largeBacklogDeletesAcrossBoundedBatches() {
		List<Notification> seeded = new ArrayList<>();
		for (int i = 0; i < NotificationRetentionService.DELETE_BATCH_SIZE + 5; i++) {
			seeded.add(seed(customerA, true, daysAgo(100)));
		}
		Notification recent = seed(customerA, true, daysAgo(1));

		NotificationRetentionService.PurgeSummary summary = retention.purgeOnce();

		assertThat(summary.deletedRead())
				.isEqualTo(NotificationRetentionService.DELETE_BATCH_SIZE + 5);
		for (Notification notification : seeded) {
			assertThat(existsInDb(notification.getId())).isFalse();
		}
		assertThat(existsInDb(recent.getId())).isTrue();
	}

	@Test
	void emptyTablePurgesNothing() {
		NotificationRetentionService.PurgeSummary summary = retention.purgeOnce();
		assertThat(summary.deletedRead()).isEqualTo(0);
		assertThat(summary.deletedUnread()).isEqualTo(0);
		assertThat(summary.readCutoff()).isEqualTo(NOW.minus(policy.getReadAfter()));
		assertThat(summary.unreadCutoff()).isEqualTo(NOW.minus(policy.getUnreadAfter()));
	}

	@Test
	void repositoryFailureDoesNotEscapeTheScheduledTick() {
		NotificationRepository failing = Mockito.mock(NotificationRepository.class);
		Mockito.when(failing.findReadIdsEligibleForRetention(
				Mockito.any(Instant.class), Mockito.any()))
				.thenThrow(new RuntimeException("database down"));
		NotificationRetentionService fragile = new NotificationRetentionService(
				policy, failing, clock, transactionManager);

		// purgeOnce propagates (callers decide); the tick itself must
		// never throw outward — the scheduler stays alive for the next run.
		org.assertj.core.api.Assertions.assertThatThrownBy(fragile::purgeOnce)
				.isInstanceOf(RuntimeException.class);
		fragile.scheduledCleanup();
		Mockito.verify(failing, Mockito.atLeastOnce()).findReadIdsEligibleForRetention(
				Mockito.any(Instant.class), Mockito.any());
	}
}
