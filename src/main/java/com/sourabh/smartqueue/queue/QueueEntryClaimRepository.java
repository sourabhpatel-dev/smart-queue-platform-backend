package com.sourabh.smartqueue.queue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The atomic queue claim (see ADR-012, aging in ADR-014).
 *
 * <p>One native query selects the highest-ranked WAITING entry across the
 * queues a counter may serve and locks it with {@code FOR UPDATE SKIP
 * LOCKED} in the same statement: two concurrent callers can never receive
 * the same row — the loser skips the locked row and takes the next eligible
 * one instead of blocking.
 *
 * <p>Ranking is computed in SQL so selection stays atomic (no fetch-sort-
 * relock race): EMERGENCY first by construction (hard ceiling — aging can
 * never outrank it), then a numeric score of base weight
 * (PRIORITY&nbsp;=&nbsp;1, else&nbsp;0) plus capped aging steps
 * ({@code floor(waitMinutes / intervalMinutes)}, at most
 * {@code maxPromotions}), then {@code joined_at}, then id. Nothing depends
 * on physical row order; the persisted priority is never modified.
 *
 * <p>A standalone component (not a Spring Data fragment): the locking SQL
 * is the whole responsibility, and it deserves to be read as SQL.
 */
@Repository
public class QueueEntryClaimRepository {

	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * @param nowEpochSeconds current time as epoch seconds (from the project
	 * {@code Clock} — a plain long binds cleanly, unlike {@code Instant}).
	 * @param aging the effective policy; disabled reproduces strict priority.
	 * @return the locked next entry, or empty if none is waiting (or all
	 * eligible rows are currently locked by others).
	 */
	@SuppressWarnings("unchecked")
	public Optional<QueueEntry> claimNext(UUID branchId, List<UUID> serviceIds,
			long nowEpochSeconds, QueueAgingProperties aging) {
		List<QueueEntry> rows = entityManager.createNativeQuery(
				"""
				SELECT e.* FROM queue_entry e
				JOIN queue q ON e.queue_id = q.id
				WHERE q.branch_id = :branchId
				  AND q.service_id IN (:serviceIds)
				  AND q.status = 'ACTIVE'
				  AND e.status = 'WAITING'
				ORDER BY
				"""
						+ QueueRankingSql.orderByClause() + """
				
				LIMIT 1 FOR UPDATE OF e SKIP LOCKED
				""",
				QueueEntry.class)
				.setParameter("branchId", branchId)
				.setParameter("serviceIds", serviceIds)
				.setParameter("nowEpoch", nowEpochSeconds)
				.setParameter("intervalMinutes", aging.getIntervalMinutes())
				.setParameter("maxPromotions", aging.getMaxPromotions())
				.setParameter("agingEnabled", aging.isEnabled())
				.getResultList();
		return rows.stream().findFirst();
	}
}
