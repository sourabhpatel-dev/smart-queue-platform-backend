package com.sourabh.smartqueue.notification.api;

import java.time.Instant;
import java.util.UUID;

import com.sourabh.smartqueue.notification.Notification;

/**
 * Public notification view (see ADR-032).
 *
 * <p>API-level data only: UUIDs, the type/reference discriminators as
 * name strings, the rendered title/message, a derived {@code read}
 * flag, and ISO-8601 timestamps. No organization id (the recipient
 * scope is the caller themself), no recipient identity, no entities,
 * no credentials. Explicit manual mapping, no framework. The same
 * shape travels over the realtime user destination.
 */
public record NotificationResponse(
		UUID id,
		String type,
		String title,
		String message,
		boolean read,
		Instant readAt,
		Instant createdAt,
		String referenceType,
		UUID referenceId) {

	public static NotificationResponse from(Notification notification) {
		return new NotificationResponse(
				notification.getId(),
				notification.getType().name(),
				notification.getTitle(),
				notification.getMessage(),
				notification.isRead(),
				notification.getReadAt(),
				notification.getCreatedAt(),
				notification.getReferenceType() == null
						? null
						: notification.getReferenceType().name(),
				notification.getReferenceId());
	}
}
