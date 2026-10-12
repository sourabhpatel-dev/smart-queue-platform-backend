package com.sourabh.smartqueue.service;

/**
 * Lifecycle states for a Service (see ADR-008).
 *
 * <p>Only the two states the domain needs today. Persisted by name, never by
 * ordinal.
 */
public enum ServiceStatus {
	ACTIVE,
	INACTIVE
}
