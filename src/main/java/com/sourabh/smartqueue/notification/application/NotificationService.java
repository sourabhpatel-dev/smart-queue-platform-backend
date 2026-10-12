package com.sourabh.smartqueue.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.common.api.ApiPage;
import com.sourabh.smartqueue.common.api.ApiPageRequest;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.notification.Notification;
import com.sourabh.smartqueue.notification.NotificationRepository;
import com.sourabh.smartqueue.notification.NotificationSender;
import com.sourabh.smartqueue.notification.NotificationType;
import com.sourabh.smartqueue.notification.api.NotificationResponse;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * In-app notification boundary (see ADR-032).
 *
 * <p>Two responsibilities, one ordering guarantee:
 *
 * <ol>
 *   <li><strong>Persist first.</strong> {@link #create} inserts the row
 *   in its own transaction (callers are AFTER_COMMIT listeners, so the
 *   business fact already committed — a notification failure can never
 *   roll business state back, and a business rollback never reaches
 *   this code). The {@code uq_notification_event} backstop turns a
 *   repeated handling of the same source event into a logged no-op.</li>
 *   <li><strong>Deliver best-effort, after commit.</strong> The realtime
 *   echo goes through {@link NotificationSender} in an
 *   {@code afterCommit} synchronization — never before the row
 *   commits, so a client can never receive a notification the center
 *   cannot re-read. Any failure is caught and logged — the row stays,
 *   the notification center re-reads it.</li>
 * </ol>
 *
 * <p>Reads are recipient-scoped to the principal: no user id is ever
 * accepted from the client, and rows outside the caller's organization
 * (or user) yield the same scope-safe {@code 404}.
 */
@Service
public class NotificationService {

	static final String CODE = "NOTIFICATION_NOT_FOUND";

	/** API sort names to persistence properties — the allow-list, nothing else. */
	static final Map<String, String> SORTABLE = Map.of(
			"createdAt", "createdAt",
			"readAt", "readAt");

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	private final NotificationRepository notifications;
	private final OrganizationRepository organizations;
	private final UserRepository users;
	private final NotificationSender sender;
	private final Clock clock;

	public NotificationService(NotificationRepository notifications,
			OrganizationRepository organizations,
			UserRepository users,
			NotificationSender sender,
			Clock clock) {
		this.notifications = notifications;
		this.users = users;
		this.organizations = organizations;
		this.sender = sender;
		this.clock = clock;
	}

	/**
	 * Persists one notification for the event's customer and attempts the
	 * realtime echo. Returns empty when the fact was already notified
	 * (duplicate handling) or the recipient cannot be resolved safely
	 * (unknown user or cross-organization id — never guessed).
	 *
	 * <p>Runs in its own transaction on purpose: callers are
	 * AFTER_COMMIT listeners, which execute while the business
	 * transaction is completing — joining it (the REQUIRED default)
	 * would flush against an already-committed session and fail, and
	 * any failure here must stay isolated from business state anyway.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public java.util.Optional<NotificationResponse> create(UUID organizationId,
			UUID recipientUserId, NotificationType type, String title,
			String message,
			com.sourabh.smartqueue.notification.NotificationReferenceType referenceType,
			UUID referenceId, UUID eventId) {
		if (notifications.existsByEventId(eventId)) {
			log.info("notification for event {} already exists; skipping duplicate handling", eventId);
			return java.util.Optional.empty();
		}
		Organization organization = organizations.findById(organizationId).orElse(null);
		User recipient = users.findById(recipientUserId).orElse(null);
		if (organization == null || recipient == null) {
			log.warn("skipping notification for event {}: unresolvable recipient scope", eventId);
			return java.util.Optional.empty();
		}
		Notification notification = new Notification(organization, recipient, type,
				title, message, referenceType, referenceId, eventId);
		try {
			notifications.saveAndFlush(notification);
		} catch (DataIntegrityViolationException e) {
			// Lost a concurrent handling race on uq_notification_event: the
			// other handling won and its row is the notification. No retry,
			// no duplicate — the fact is notified exactly once in storage.
			log.info("notification for event {} raced another handling; keeping the existing row", eventId);
			return java.util.Optional.empty();
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					sender.send(notification);
				} catch (RuntimeException e) {
					// Best-effort echo only: the committed row is the
					// notification. The center re-reads it; nothing here
					// may throw back into the AFTER_COMMIT listener chain.
					log.warn("realtime notification delivery failed for notification {}",
							notification.getId(), e);
				}
			}
		});
		return java.util.Optional.of(NotificationResponse.from(notification));
	}

	@Transactional(readOnly = true)
	public ApiPage<NotificationResponse> listNotifications(AuthenticatedPrincipal principal,
			int page, int size, List<String> sorts, String unreadFilter) {
		Pageable pageable = ApiPageRequest.of(page, size, sorts, SORTABLE,
				"createdAt", org.springframework.data.domain.Sort.Direction.DESC);
		Page<Notification> result;
		if (unreadFilter == null) {
			result = notifications.findByRecipientUserId(principal.userId(), pageable);
		} else if (unreadFilter.equalsIgnoreCase("true")) {
			result = notifications.findByRecipientUserIdAndReadAtIsNull(principal.userId(), pageable);
		} else if (unreadFilter.equalsIgnoreCase("false")) {
			result = notifications.findByRecipientUserIdAndReadAtIsNotNull(principal.userId(), pageable);
		} else {
			throw new InvalidRequestException("INVALID_REQUEST",
					"Invalid unread filter: " + unreadFilter + ". Use true or false.");
		}
		return ApiPage.from(result.map(NotificationResponse::from));
	}

	@Transactional(readOnly = true)
	public NotificationResponse getNotification(AuthenticatedPrincipal principal, UUID notificationId) {
		return NotificationResponse.from(scopedNotification(principal, notificationId));
	}

	/** Idempotent: reading an already-read row keeps the first stamp. */
	@Transactional
	public NotificationResponse markRead(AuthenticatedPrincipal principal, UUID notificationId) {
		Notification notification = scopedNotification(principal, notificationId);
		notification.markRead(clock.instant());
		return NotificationResponse.from(notifications.save(notification));
	}

	@Transactional
	public int markAllRead(AuthenticatedPrincipal principal) {
		return notifications.markAllRead(principal.userId(), clock.instant());
	}

	@Transactional(readOnly = true)
	public long unreadCount(AuthenticatedPrincipal principal) {
		return notifications.countByRecipientUserIdAndReadAtIsNull(principal.userId());
	}

	private Notification scopedNotification(AuthenticatedPrincipal principal, UUID notificationId) {
		Notification notification = notifications
				.findByIdAndRecipientUserId(notificationId, principal.userId())
				.orElseThrow(() -> new ResourceNotFoundException(CODE, "Notification not found"));
		// Recipient ownership is the scope for customers: their rows span
		// every organization they act in, with no membership required
		// (ADR-040). Operational principals additionally must sit in the
		// row's organization.
		if (principal.isOperational()
				&& !notification.getOrganization().getId().equals(principal.organizationId())) {
			throw new ResourceNotFoundException(CODE, "Notification not found");
		}
		return notification;
	}
}
