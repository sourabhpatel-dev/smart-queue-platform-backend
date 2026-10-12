package com.sourabh.smartqueue.queue.realtime;

import com.sourabh.smartqueue.queue.QueueEntryStatus;
import com.sourabh.smartqueue.queue.QueuePriority;

/**
 * One customer's public display row inside a queue snapshot (see ADR-018).
 *
 * <p>Deliberately customer-anonymous: token number, priority, status and an
 * estimated wait — never customer UUIDs, emails, roles, or any staff
 * identity. Tokens are the public identifiers clients already understand.
 *
 * @param tokenNumber the walk-in token (customer-facing identity)
 * @param priority the persisted base priority (aging is already reflected
 * in list position, never by mutating this value)
 * @param status WAITING, CALLED or SERVING (snapshots never carry terminal rows)
 * @param estimatedWaitMinutes whole minutes until expected service start,
 * or {@code null} when no serving capacity exists right now
 */
public record QueueEntryView(
		int tokenNumber,
		QueuePriority priority,
		QueueEntryStatus status,
		Integer estimatedWaitMinutes) {
}
