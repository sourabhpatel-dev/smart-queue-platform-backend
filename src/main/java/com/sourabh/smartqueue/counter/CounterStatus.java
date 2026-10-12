package com.sourabh.smartqueue.counter;

/**
 * Lifecycle states for a ServiceCounter (see ADR-008).
 *
 * <p>Administrative states only. Queue-runtime states ({@code BUSY},
 * {@code SERVING}, …) are deliberately absent: they describe live operations
 * and will be modeled with the service-session work, not as a static counter
 * attribute. Persisted by name, never by ordinal.
 */
public enum CounterStatus {
	ACTIVE,
	INACTIVE,
	MAINTENANCE
}
