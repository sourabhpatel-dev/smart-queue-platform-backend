package com.sourabh.smartqueue.membership;

/**
 * Organization-scoped operational role vocabulary for
 * {@link OrganizationMembership} (see ADR-040).
 *
 * <p>Deliberately narrower than the platform-level {@code RoleType}
 * (CUSTOMER/STAFF/MANAGER/ADMIN): a customer is a <em>platform-level</em>
 * identity and never becomes an organization member, so CUSTOMER has no
 * membership representation. Typing the column with this enum makes a
 * customer membership row impossible at compile time rather than merely
 * forbidden by convention.
 *
 * <p>Privilege order ADMIN &gt; MANAGER &gt; STAFF is a documented property
 * (used by the V20 backfill when a legacy user held several operational
 * roles at one organization) and is intentionally NOT encoded here — the
 * enum carries vocabulary, and ordering lives where it is used. Persisted
 * by name, never by ordinal, like every other enum in the schema.
 */
public enum MembershipRole {
	STAFF,
	MANAGER,
	ADMIN
}
