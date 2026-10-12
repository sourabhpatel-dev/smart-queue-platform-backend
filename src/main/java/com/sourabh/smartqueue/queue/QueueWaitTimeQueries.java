package com.sourabh.smartqueue.queue;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Read-only rank/count queries shared by wait-time estimation and the
 * real-time projection (see ADR-015).
 *
 * <p>Public because the realtime subpackage builds snapshots from it;
 * still an internal query component, not an application service.
 */
@Repository
public class QueueWaitTimeQueries {

	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * WAITING entries of one queue in claim rank order (shared fragment —
	 * identical ordering to the atomic claim). Snapshot and estimator
	 * position both derive from this, never from a second implementation.
	 */
	@SuppressWarnings("unchecked")
	public List<QueueEntry> waitingInRankOrder(UUID queueId,
			long nowEpoch, int intervalMinutes, int maxPromotions, boolean agingEnabled) {
		List<QueueEntry> rows = entityManager.createNativeQuery(
				"""
				SELECT e.* FROM queue_entry e
				WHERE e.queue_id = :queueId
				  AND e.status = 'WAITING'
				ORDER BY
				"""
						+ QueueRankingSql.orderByClause(),
				QueueEntry.class)
				.setParameter("queueId", queueId)
				.setParameter("nowEpoch", nowEpoch)
				.setParameter("intervalMinutes", intervalMinutes)
				.setParameter("maxPromotions", maxPromotions)
				.setParameter("agingEnabled", agingEnabled)
				.getResultList();
		return rows;
	}

	/**
	 * WAITING entry ids across a counter's servable queues, in claim rank
	 * order. Position in this list is the number of entries that would be
	 * served first — shared with the atomic claim by construction.
	 */
	@SuppressWarnings("unchecked")
	public List<UUID> waitingIdsInRankOrder(UUID branchId, List<UUID> serviceIds,
			long nowEpoch, int intervalMinutes, int maxPromotions, boolean agingEnabled) {
		List<Object> ids = entityManager.createNativeQuery(
				"""
				SELECT e.id FROM queue_entry e
				JOIN queue q ON e.queue_id = q.id
				WHERE q.branch_id = :branchId
				  AND q.service_id IN (:serviceIds)
				  AND q.status = 'ACTIVE'
				  AND e.status = 'WAITING'
				ORDER BY
				"""
						+ QueueRankingSql.orderByClause())
				.setParameter("branchId", branchId)
				.setParameter("serviceIds", serviceIds)
				.setParameter("nowEpoch", nowEpoch)
				.setParameter("intervalMinutes", intervalMinutes)
				.setParameter("maxPromotions", maxPromotions)
				.setParameter("agingEnabled", agingEnabled)
				.getResultList();
		return ids.stream().map(id -> (UUID) id).toList();
	}

	/**
	 * CALLED entries in scope: already claimed, about to occupy counters,
	 * so each counts as one work unit ahead of any WAITING entry.
	 */
	long countCalledInScope(UUID branchId, List<UUID> serviceIds) {
		Number count = (Number) entityManager.createNativeQuery(
				"""
				SELECT COUNT(*) FROM queue_entry e
				JOIN queue q ON e.queue_id = q.id
				WHERE q.branch_id = :branchId
				  AND q.service_id IN (:serviceIds)
				  AND q.status = 'ACTIVE'
				  AND e.status = 'CALLED'
				""")
				.setParameter("branchId", branchId)
				.setParameter("serviceIds", serviceIds)
				.getSingleResult();
		return count.longValue();
	}

	/**
	 * Open service sessions on the given counters: in-flight work already
	 * consuming serving capacity.
	 */
	long countActiveSessions(List<UUID> counterIds) {
		if (counterIds.isEmpty()) {
			return 0;
		}
		Number count = (Number) entityManager.createNativeQuery(
				"""
				SELECT COUNT(*) FROM service_session s
				WHERE s.service_counter_id IN (:counterIds)
				  AND s.ended_at IS NULL
				""")
				.setParameter("counterIds", counterIds)
				.getSingleResult();
		return count.longValue();
	}
}
