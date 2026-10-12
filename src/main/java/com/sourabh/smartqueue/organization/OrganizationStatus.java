package com.sourabh.smartqueue.organization;

/**
 * Lifecycle states for an Organization (see ADR-007, ADR-041).
 *
 * <p>Business applications start as {@code PENDING_APPROVAL} and reach
 * {@code ACTIVE} only through authorized platform review after owner email
 * verification; rejected applications are {@code REJECTED}. Only
 * {@code ACTIVE} organizations are publicly discoverable and bookable.
 * {@code INACTIVE} remains the deactivation state for formerly active
 * organizations. Persisted by name ({@code PENDING_APPROVAL}/
 * {@code ACTIVE}/{@code REJECTED}/{@code INACTIVE}), never by ordinal,
 * so reordering or adding states later cannot corrupt stored values.
 */
public enum OrganizationStatus {
	/** A submitted business application awaiting verification + platform review. */
	PENDING_APPROVAL,
	/** Approved and usable: the only discoverable/bookable state. */
	ACTIVE,
	/** Rejected by platform review: never discoverable or bookable. */
	REJECTED,
	/** Deactivated formerly-active organization (pre-existing state). */
	INACTIVE
}
