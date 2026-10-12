package com.sourabh.smartqueue.notification;

/**
 * Realtime delivery port for persisted notifications (see ADR-032).
 *
 * <p>Persistence and delivery are separate steps on purpose: the
 * notification row commits first (PostgreSQL authoritative), then an
 * implementation of this port attempts the live echo. Delivery
 * failures are caught by the caller and never roll anything back —
 * the notification center re-reads the row later. Only
 * {@code IN_APP} exists in this phase; email/SMS/push senders are
 * explicitly deferred, and this interface must stay this small so
 * adding one never touches business workflows.
 */
public interface NotificationSender {

	/**
	 * Attempts live delivery of an already-persisted notification.
	 *
	 * @throws RuntimeException when delivery fails; callers log and
	 * continue — the persisted row is the source of truth.
	 */
	void send(Notification notification);
}
