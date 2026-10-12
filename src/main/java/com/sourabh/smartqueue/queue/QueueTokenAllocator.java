package com.sourabh.smartqueue.queue;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates walk-in token numbers inside the caller's transaction
 * (see ADR-012).
 *
 * <p>Two-step, same-transaction protocol: a conflict-free
 * {@code INSERT … ON CONFLICT DO NOTHING} guarantees the day's counter row
 * exists (first join of the day wins the insert, losers proceed — no
 * exception, no poisoned session, no retry loop), then
 * {@code SELECT … FOR UPDATE} takes the number under the row lock so
 * concurrent joins serialize instead of colliding. Committed increments are
 * never rolled back independently, so token numbers may gap under
 * contention — uniqueness, not gaplessness, is the guarantee.
 */
@Service
class QueueTokenAllocator {

	private final QueueTokenCounterRepository counters;

	QueueTokenAllocator(QueueTokenCounterRepository counters) {
		this.counters = counters;
	}

	@Transactional
	public int allocate(UUID queueId, LocalDate businessDate) {
		counters.insertIfAbsent(UUID.randomUUID(), queueId, businessDate);
		QueueTokenCounter counter = counters.findLockedByQueueAndDate(queueId, businessDate)
				.orElseThrow(() -> new QueueConflictException(
						"token counter row missing after conflict-free insert"));
		int token = counter.getNextNumber();
		counter.advance();
		return token;
	}
}
