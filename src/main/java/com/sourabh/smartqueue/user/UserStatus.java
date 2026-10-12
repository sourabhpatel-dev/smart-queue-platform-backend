package com.sourabh.smartqueue.user;

/**
 * Lifecycle states for a User (see ADR-009).
 *
 * <p>Only the two states the domain needs today. Persisted by name, never by
 * ordinal. Authentication-related states (locked, credentials-expired) belong
 * to the future security phase, not to this domain enum.
 */
public enum UserStatus {
	ACTIVE,
	INACTIVE
}
