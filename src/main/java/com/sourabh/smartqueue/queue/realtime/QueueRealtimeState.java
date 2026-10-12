package com.sourabh.smartqueue.queue.realtime;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.sourabh.smartqueue.queue.QueueStatus;

/**
 * The public real-time state of one queue at one instant (see ADR-018).
 *
 * <p>A wholesale snapshot, not a mutation stream: a client receiving it
 * needs no history to render current state, a missed message heals on the
 * next snapshot, and re-applying the same snapshot is harmless. Redis and
 * WebSocket carry the same record — one model, two transports.
 *
 * <p>Honest limitations travel with the design, not the payload:
 * {@code generatedAt} lets clients notice staleness but is not a global
 * sequence (concurrent refreshes resolve last-write-wins); wait values are
 * rebuilt on every business event and, for active queues, on every
 * scheduled freshness tick (ADR-020) — bounded drift, never a promise.
 */
public record QueueRealtimeState(
		UUID organizationId,
		UUID branchId,
		UUID queueId,
		UUID serviceId,
		QueueStatus queueStatus,
		Instant generatedAt,
		int waitingCount,
		int calledCount,
		int servingCount,
		List<QueueEntryView> waiting,
		List<Integer> calledTokens,
		List<Integer> servingTokens) {

	/**
	 * Whether two snapshots carry the same live queue content (see ADR-020).
	 *
	 * <p>{@code generatedAt} is deliberately excluded: every rebuild stamps
	 * a new instant, so comparing it would always report a change and the
	 * scheduled refresh could never stay quiet. Everything else — tenant
	 * scope, queue status, counts, rank-ordered waiting rows with their
	 * waits, called/serving tokens — must match. Rank order matters: the
	 * list comparison is order-sensitive, so an aging-driven reorder counts
	 * as a material change even when the entry set is identical.
	 */
	public static boolean sameLiveContent(QueueRealtimeState left, QueueRealtimeState right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return Objects.equals(left.organizationId(), right.organizationId())
				&& Objects.equals(left.branchId(), right.branchId())
				&& Objects.equals(left.queueId(), right.queueId())
				&& Objects.equals(left.serviceId(), right.serviceId())
				&& left.queueStatus() == right.queueStatus()
				&& left.waitingCount() == right.waitingCount()
				&& left.calledCount() == right.calledCount()
				&& left.servingCount() == right.servingCount()
				&& Objects.equals(left.waiting(), right.waiting())
				&& Objects.equals(left.calledTokens(), right.calledTokens())
				&& Objects.equals(left.servingTokens(), right.servingTokens());
	}
}
