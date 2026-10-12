package com.sourabh.smartqueue.notification;

/**
 * STOMP destination convention for in-app notifications (see ADR-032).
 *
 * <p>Single construction point. Clients subscribe to the fixed user
 * destination {@code /user/queue/notifications}; the server delivers
 * with {@code convertAndSendToUser(userId, USER_QUEUE, …)}, so the
 * recipient is always the handshake-bound session user — no id in any
 * subscribable path, nothing to spoof. (The `/user` prefix is Spring's
 * per-session routing; the channel interceptor explicitly allows this
 * one destination and denies everything else unknown.)
 */
public final class NotificationDestinations {

	/** Server-side send base for {@code convertAndSendToUser}. */
	public static final String USER_QUEUE = "/queue/notifications";

	/** Client subscription destination. */
	public static final String USER_DESTINATION = "/user" + USER_QUEUE;

	private NotificationDestinations() {
	}
}
