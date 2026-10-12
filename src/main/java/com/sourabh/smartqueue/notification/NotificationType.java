package com.sourabh.smartqueue.notification;

/**
 * In-app notification vocabulary (see ADR-032).
 *
 * <p>Deliberately small: one type per customer-meaningful committed fact
 * that already carries a safe recipient ({@code customerId}) in its
 * source event — plus {@code APPOINTMENT_REMINDER}, whose recipient
 * is the appointment's own customer (resolved from PostgreSQL by the
 * reminder sweep, never from a request). No staff-broadcast types
 * exist — the current events cannot name a safe staff recipient
 * without guessing, so none is attempted (documented limitation, not
 * an oversight). Appointment cancel/complete/no-show flows publish no
 * events today, so they have no types either. String-persisted, never
 * ordinals.
 */
public enum NotificationType {

	APPOINTMENT_BOOKED,
	APPOINTMENT_REMINDER,
	QUEUE_JOINED,
	QUEUE_CALLED,
	QUEUE_COMPLETED,
	QUEUE_CANCELLED,
	QUEUE_NO_SHOW
}
