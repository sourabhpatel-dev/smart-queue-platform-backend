package com.sourabh.smartqueue.membership;

/**
 * Lifecycle states for an {@link OrganizationMembership} (see ADR-040).
 *
 * <p>Administrative states only, mirroring the convention of the other
 * status enums (e.g. {@code StaffAssignmentStatus}). The binding invariant
 * of the membership model: <strong>only ACTIVE membership grants
 * organization-scoped operational access</strong> — an INACTIVE row keeps
 * the historical grant but confers nothing.
 *
 * <p>Not reused from {@code StaffAssignmentStatus}: membership is its own
 * concept with its own lifecycle, and importing the staff package's enum
 * would couple two models that ADR-040 deliberately separates. Persisted
 * by name, never by ordinal.
 */
public enum MembershipStatus {
	ACTIVE,
	INACTIVE
}
