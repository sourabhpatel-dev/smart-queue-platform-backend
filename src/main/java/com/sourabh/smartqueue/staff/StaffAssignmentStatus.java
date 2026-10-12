package com.sourabh.smartqueue.staff;

/**
 * Lifecycle states for a StaffAssignment (see ADR-009).
 *
 * <p>Administrative states only. Runtime presence states ({@code SERVING},
 * {@code BUSY}, {@code OFFLINE}, …) describe live queue operations and will
 * be modeled with the service-session work, not here. Persisted by name,
 * never by ordinal.
 */
public enum StaffAssignmentStatus {
	ACTIVE,
	INACTIVE
}
