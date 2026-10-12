package com.sourabh.smartqueue.queue;

/**
 * Lifecycle states for a Queue (see ADR-012).
 *
 * <p>Administrative only: an INACTIVE queue rejects new walk-ins while its
 * history stays queryable. No runtime states ({@code BUSY}, {@code PAUSED})
 * — the queue is a logical line, not a machine. Persisted by name, never by
 * ordinal.
 */
public enum QueueStatus {
	ACTIVE,
	INACTIVE
}
