package com.sourabh.smartqueue.notification;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.sourabh.smartqueue.common.BaseAuditableEntity;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.user.User;

/**
 * One persisted in-app notification for one recipient (see ADR-032).
 *
 * <p>Creation-only semantics: rows are inserted by the notification
 * service after their source fact commits and never updated except for
 * the {@code readAt} stamp (mark-read is the sole mutation, no PUT
 * exists). No entities travel inside — {@code title}/{@code message}
 * are rendered at creation from the recipient's own data, and the
 * reference is an id pair for navigation. {@code eventId} is the
 * source domain event's id with a uniqueness backstop, so repeated
 * handling of the same fact cannot duplicate the row.
 */
@Entity
@Table(name = "notification")
public class Notification extends BaseAuditableEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "organization_id",
			referencedColumnName = "id",
			foreignKey = @ForeignKey(name = "fk_notification_organization"),
			nullable = false,
			updatable = false)
	private Organization organization;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "recipient_user_id",
			referencedColumnName = "id",
			foreignKey = @ForeignKey(name = "fk_notification_recipient"),
			nullable = false,
			updatable = false)
	private User recipientUser;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 40, updatable = false)
	private NotificationType type;

	@Column(name = "title", nullable = false, length = 150, updatable = false)
	private String title;

	@Column(name = "message", nullable = false, length = 1000, updatable = false)
	private String message;

	@Enumerated(EnumType.STRING)
	@Column(name = "reference_type", length = 20, updatable = false)
	private NotificationReferenceType referenceType;

	@Column(name = "reference_id", updatable = false)
	private UUID referenceId;

	@Column(name = "event_id", nullable = false, updatable = false)
	private UUID eventId;

	@Column(name = "read_at")
	private Instant readAt;

	protected Notification() {
		// JPA requires a no-arg constructor.
	}

	public Notification(Organization organization, User recipientUser,
			NotificationType type, String title, String message,
			NotificationReferenceType referenceType, UUID referenceId,
			UUID eventId) {
		if (organization == null) {
			throw new IllegalArgumentException("organization is required");
		}
		if (recipientUser == null) {
			throw new IllegalArgumentException("recipient user is required");
		}
		if (type == null) {
			throw new IllegalArgumentException("type is required");
		}
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("title is required");
		}
		if (message == null || message.isBlank()) {
			throw new IllegalArgumentException("message is required");
		}
		if (eventId == null) {
			throw new IllegalArgumentException("event id is required");
		}
		// The notification's organization is where the fact happened, not a
		// claim about the recipient: a customer receives notifications
		// from organizations they book at or join queues at, with no
		// membership required (ADR-040). The legacy equality check would
		// have rejected exactly those rows. It survives only as a backstop
		// for operational recipients.
		boolean recipientIsOperational = recipientUser.getRoles().stream()
				.anyMatch(role -> role != com.sourabh.smartqueue.user.RoleType.CUSTOMER);
		if (recipientIsOperational && recipientUser.getOrganization() != null
				&& !recipientUser.getOrganization().getId().equals(organization.getId())) {
			throw new IllegalArgumentException("recipient must belong to the notification organization");
		}
		this.organization = organization;
		this.recipientUser = recipientUser;
		this.type = type;
		this.title = title;
		this.message = message;
		this.referenceType = referenceType;
		this.referenceId = referenceId;
		this.eventId = eventId;
	}

	public Organization getOrganization() {
		return organization;
	}

	public User getRecipientUser() {
		return recipientUser;
	}

	public NotificationType getType() {
		return type;
	}

	public String getTitle() {
		return title;
	}

	public String getMessage() {
		return message;
	}

	public NotificationReferenceType getReferenceType() {
		return referenceType;
	}

	public UUID getReferenceId() {
		return referenceId;
	}

	public UUID getEventId() {
		return eventId;
	}

	public Instant getReadAt() {
		return readAt;
	}

	public boolean isRead() {
		return readAt != null;
	}

	/** Idempotent: stamping an already-read row keeps the first stamp. */
	public void markRead(Instant readAt) {
		if (this.readAt == null) {
			this.readAt = readAt;
		}
	}
}
