package com.sourabh.smartqueue.queue;

/**
 * Lifecycle states for a QueueEntry (see ADR-012).
 *
 * <p>{@code CALLED} (selected by a counter, service not yet started) stays
 * separate from {@code SERVING} (interaction started) — the future
 * ServiceSession phase needs that boundary. Terminal states
 * ({@code COMPLETED}, {@code CANCELLED}, {@code NO_SHOW}) free the
 * customer's active-entry slot. Persisted by name, never by ordinal.
 */
public enum QueueEntryStatus {
	WAITING,
	CALLED,
	SERVING,
	COMPLETED,
	CANCELLED,
	NO_SHOW
}
