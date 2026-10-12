package com.sourabh.smartqueue.queue.realtime;

import java.util.UUID;

/**
 * STOMP destination convention for real-time queue state (see ADR-018).
 *
 * <p>Single construction point, tenant scope always explicit:
 * {@code /topic/organizations/{o}/branches/{b}/queues/{q}}. Clients
 * subscribe for ongoing snapshots; the snapshot-request destination
 * ({@code /app} + same tail + {@code /snapshot}) triggers a fresh publish
 * to the topic. Destinations authorize nothing — the security phase plugs
 * authorization onto exactly these paths.
 */
public final class QueueRealtimeDestinations {

	private QueueRealtimeDestinations() {
	}

	public static String topic(UUID organizationId, UUID branchId, UUID queueId) {
		return "/topic/organizations/" + organizationId
				+ "/branches/" + branchId
				+ "/queues/" + queueId;
	}

	public static String snapshotRequest(UUID organizationId, UUID branchId, UUID queueId) {
		return "/app/organizations/" + organizationId
				+ "/branches/" + branchId
				+ "/queues/" + queueId + "/snapshot";
	}
}
