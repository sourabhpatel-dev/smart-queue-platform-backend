package com.sourabh.smartqueue.queue;

/**
 * Walk-in urgency, highest first (see ADR-012).
 *
 * <p>Strict priority with FIFO inside each level — deterministic and
 * explainable. Deliberately NO aging yet: strict priority can starve NORMAL
 * entries, and the later queue-algorithm phase owns that trade-off openly
 * rather than hiding a half-measure here. Persisted by name, never by
 * ordinal.
 */
public enum QueuePriority {
	NORMAL,
	PRIORITY,
	EMERGENCY
}
