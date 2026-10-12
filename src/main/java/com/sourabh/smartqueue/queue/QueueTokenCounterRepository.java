package com.sourabh.smartqueue.queue;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/**
 * Persistence access for {@link QueueTokenCounter}.
 *
 * <p>The locked finder serializes concurrent joins on the day's counter row
 * ({@code SELECT … FOR UPDATE}); the conflict-free insert guarantees the
 * row exists first — the two mechanisms behind race-safe tokens.
 */
public interface QueueTokenCounterRepository extends JpaRepository<QueueTokenCounter, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT c FROM QueueTokenCounter c WHERE c.queue.id = :queueId AND c.businessDate = :date")
	Optional<QueueTokenCounter> findLockedByQueueAndDate(
			@Param("queueId") UUID queueId, @Param("date") LocalDate date);

	/**
	 * Creates the day's counter row unless a concurrent join already did.
	 * {@code ON CONFLICT DO NOTHING} (on the unique) instead of catching a
	 * violation: no exception ever enters the persistence context, so the
	 * surrounding transaction stays healthy and the retry loop disappears.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO queue_token_counter
				(id, queue_id, business_date, next_number, created_at, updated_at)
			VALUES (:id, :queueId, :date, 1, NOW(), NOW())
			ON CONFLICT (queue_id, business_date) DO NOTHING
			""",
			nativeQuery = true)
	void insertIfAbsent(@Param("id") UUID id, @Param("queueId") UUID queueId,
			@Param("date") LocalDate date);
}
