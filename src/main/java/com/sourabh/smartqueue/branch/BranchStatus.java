package com.sourabh.smartqueue.branch;

/**
 * Lifecycle states for a Branch (see ADR-007).
 *
 * <p>Per-feature enum (not shared with Organization): each feature owns its
 * vocabulary so lifecycles can diverge later. Persisted by name, never by
 * ordinal.
 */
public enum BranchStatus {
	ACTIVE,
	INACTIVE
}
