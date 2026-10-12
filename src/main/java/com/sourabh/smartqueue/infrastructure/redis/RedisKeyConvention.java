package com.sourabh.smartqueue.infrastructure.redis;

import java.util.Objects;
import java.util.UUID;

/**
 * Project-wide Redis key convention (see ADR-017).
 *
 * <p>Shape: {@code sq:o:{organizationId}:b:{branchId}:{domain}:{id…}} —
 * the application namespace plus the full tenant scope on every key, so
 * keys can never collide across organizations or branches even though the
 * underlying UUIDs are already globally unique. Keys authorize nothing:
 * application-level tenant checks remain mandatory.
 *
 * <p>The single construction point: no ad-hoc {@code "queue:" + id}
 * literals anywhere else. An environment segment is deliberately absent —
 * local/dev/test never share a Redis; add one if that ever changes.
 */
public final class RedisKeyConvention {

	private static final String PREFIX = "sq";

	private RedisKeyConvention() {
	}

	/** Queue-level derived state (e.g. a wait snapshot), namespaced by tenant. */
	public static String queueState(UUID organizationId, UUID branchId, UUID queueId) {
		return tenantScope(organizationId, branchId) + ":queue:" + required(queueId, "queueId") + ":state";
	}

	/** One queue entry's derived state. */
	public static String queueEntry(UUID organizationId, UUID branchId, UUID queueId, UUID entryId) {
		return queueState(organizationId, branchId, queueId) + ":entry:" + required(entryId, "entryId");
	}

	/** The queue's current wait projection slot. */
	public static String queueWait(UUID organizationId, UUID branchId, UUID queueId) {
		return queueState(organizationId, branchId, queueId) + ":wait";
	}

	private static String tenantScope(UUID organizationId, UUID branchId) {
		return PREFIX + ":o:" + required(organizationId, "organizationId")
				+ ":b:" + required(branchId, "branchId");
	}

	private static UUID required(UUID id, String name) {
		return Objects.requireNonNull(id, () -> name + " must not be null for a Redis key");
	}
}
