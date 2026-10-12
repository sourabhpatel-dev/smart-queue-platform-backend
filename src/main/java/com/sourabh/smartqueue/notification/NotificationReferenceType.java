package com.sourabh.smartqueue.notification;

/**
 * What a notification points at (see ADR-032).
 *
 * <p>A navigation hint only: the id plus this discriminator lets the
 * frontend deep-link where a route already exists
 * ({@code APPOINTMENT} to the appointment detail,
 * {@code QUEUE_ENTRY} to the live queue status). A notification stays
 * useful when the referenced row changes state — the target pages
 * render their own honest empty/gone states. Null when there is
 * nothing safe to link to. String-persisted, never ordinals.
 */
public enum NotificationReferenceType {

	APPOINTMENT,
	QUEUE_ENTRY
}
