package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.notification.Notification;
import com.sourabh.smartqueue.notification.NotificationReferenceType;
import com.sourabh.smartqueue.notification.NotificationRepository;
import com.sourabh.smartqueue.notification.NotificationType;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Notification persistence rules (see ADR-032): guards, string enums,
 * read-stamp idempotency, the event uniqueness backstop, and V10.
 */
@Testcontainers
@SpringBootTest
@Transactional
class NotificationPersistenceTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	OrganizationRepository organizations;
	@Autowired
	BranchRepository branches;
	@Autowired
	UserRepository users;
	@Autowired
	NotificationRepository notifications;

	private Fixture fixture(String tag) {
		Organization org = organizations.save(new Organization("Notify " + tag));
		branches.save(new Branch(org, "Downtown", "Indore", "India", ZoneId.of("Asia/Kolkata")));
		User user = users.save(new User(org, tag + "@example.com", "Customer"));
		user.grantRole(RoleType.CUSTOMER);
		user = users.save(user);
		return new Fixture(org, user);
	}

	private record Fixture(Organization org, User user) {
	}

	private Notification notification(Fixture f, UUID eventId) {
		return new Notification(f.org(), f.user(), NotificationType.QUEUE_JOINED,
				"You joined the queue", "Token 1 at Downtown.",
				NotificationReferenceType.QUEUE_ENTRY, UUID.randomUUID(), eventId);
	}

	@Test
	void persistRoundTripWithStringEnums() {
		Fixture f = fixture(UUID.randomUUID().toString().substring(0, 8));
		Notification saved = notifications.save(notification(f, UUID.randomUUID()));

		Notification reloaded = notifications.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getType()).isEqualTo(NotificationType.QUEUE_JOINED);
		assertThat(reloaded.getReferenceType()).isEqualTo(NotificationReferenceType.QUEUE_ENTRY);
		assertThat(reloaded.isRead()).isFalse();
		assertThat(reloaded.getCreatedAt()).isNotNull();
	}

	@Test
	void customerRecipientIsNotBoundToOneOrganization() {
		// ADR-040: a customer receives notifications from organizations
		// they are not a member of. The fixture recipients are
		// CUSTOMER-role, so org-B's customer accepts org-A's row.
		Fixture a = fixture("a-" + UUID.randomUUID().toString().substring(0, 8));
		Fixture b = fixture("b-" + UUID.randomUUID().toString().substring(0, 8));
		Notification notification = new Notification(a.org(), b.user(), NotificationType.QUEUE_JOINED,
				"Title", "Message", null, null, UUID.randomUUID());

		assertThat(notification.getOrganization().getId()).isEqualTo(a.org().getId());

		// The backstop survives for operational recipients: a staff member
		// from another organization is still rejected.
		User staff = users.save(new User(a.org(),
				"staff-" + UUID.randomUUID() + "@example.com", "Staff"));
		staff.grantRole(RoleType.STAFF);
		assertThatThrownBy(() -> new Notification(b.org(), staff, NotificationType.QUEUE_JOINED,
				"Title", "Message", null, null, UUID.randomUUID()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void blankTitleAndMissingEventAreRejected() {
		Fixture f = fixture(UUID.randomUUID().toString().substring(0, 8));
		assertThatThrownBy(() -> new Notification(f.org(), f.user(), NotificationType.QUEUE_JOINED,
				"  ", "Message", null, null, UUID.randomUUID()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Notification(f.org(), f.user(), NotificationType.QUEUE_JOINED,
				"Title", "Message", null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void duplicateEventIdViolatesTheBackstop() {
		Fixture f = fixture(UUID.randomUUID().toString().substring(0, 8));
		UUID eventId = UUID.randomUUID();
		notifications.saveAndFlush(notification(f, eventId));
		assertThatThrownBy(() -> notifications.saveAndFlush(notification(f, eventId)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void markReadKeepsTheFirstStamp() {
		Fixture f = fixture(UUID.randomUUID().toString().substring(0, 8));
		Notification saved = notifications.save(notification(f, UUID.randomUUID()));
		java.time.Instant first = java.time.Instant.parse("2026-10-07T00:00:00Z");
		java.time.Instant second = java.time.Instant.parse("2026-10-08T00:00:00Z");
		saved.markRead(first);
		saved.markRead(second);
		assertThat(notifications.saveAndFlush(saved).getReadAt()).isEqualTo(first);
	}
}
