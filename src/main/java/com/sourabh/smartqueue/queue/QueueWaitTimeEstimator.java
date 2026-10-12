package com.sourabh.smartqueue.queue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.counter.CounterCapability;
import com.sourabh.smartqueue.counter.CounterCapabilityRepository;
import com.sourabh.smartqueue.counter.CounterStatus;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.tenant.TenantContextRequiredException;

/**
 * Estimated waiting time for a queue entry (see ADR-015).
 *
 * <p>Definition: whole minutes from now until the entry's expected service
 * start — <em>not</em> total time in queue, service duration, or a
 * historical average. Model: the entry sits at position
 * {@code P = ahead + active} in the service order (WAITING entries ranked
 * before it by the same semantics as claiming, plus CALLED entries, plus
 * open sessions on capable counters); with {@code C} active capable
 * counters each clearing one standard-duration unit per round, it starts
 * in round {@code floor(P / C)}, i.e. {@code floor(P / C) × S} minutes.
 *
 * <p>Deliberate approximations, documented not hidden: an active session
 * counts as one full unit (remaining time is unknowable from stored data);
 * cross-queue contention beyond the servable set is absorbed by the shared
 * capacity divisor; the result is a point-in-time snapshot that moves with
 * queue state — never a promise. Whole minutes only: the model cannot
 * justify seconds.
 */
@Service
public class QueueWaitTimeEstimator {

	private final QueueEntryRepository entries;
	private final CounterCapabilityRepository capabilities;
	private final QueueWaitTimeQueries queries;
	private final QueueRepository queues;
	private final Clock clock;
	private final QueueAgingProperties aging;

	public QueueWaitTimeEstimator(QueueEntryRepository entries,
			CounterCapabilityRepository capabilities,
			QueueWaitTimeQueries queries,
			QueueRepository queues,
			Clock clock,
			QueueAgingProperties aging) {
		this.entries = entries;
		this.capabilities = capabilities;
		this.queries = queries;
		this.queues = queues;
		this.clock = clock;
		this.aging = aging;
	}

	/**
	 * @return the estimate, or empty when none is meaningful: unknown entry,
	 * terminal entry, inactive queue, or no active capable counter (zero
	 * serving capacity — never silently zero).
	 * @throws TenantContextRequiredException if no tenant context is set.
	 */
	@Transactional(readOnly = true)
	public Optional<WaitTimeEstimate> estimate(UUID entryId) {
		var context = TenantContextHolder.get();
		QueueEntry entry = entries.findByIdAndOrganizationId(entryId, context.organizationId())
				.orElse(null);
		if (entry == null) {
			return Optional.empty();
		}
		return switch (entry.getStatus()) {
			case COMPLETED, CANCELLED, NO_SHOW -> Optional.empty();
			case CALLED, SERVING -> Optional.of(immediate(entry));
			case WAITING -> estimateWaiting(entry);
		};
	}

	private Optional<WaitTimeEstimate> estimateWaiting(QueueEntry entry) {
		var queue = entry.getQueue();
		if (queue.getStatus() != QueueStatus.ACTIVE) {
			return Optional.empty();
		}
		UUID branchId = queue.getBranch().getId();
		UUID serviceId = queue.getService().getId();

		// Serving pool: ACTIVE counters in this branch capable of the
		// entry's service — the same capability rules as claiming.
		List<ServiceCounter> capable = capabilities.findByService(queue.getService()).stream()
				.map(CounterCapability::getCounter)
				.filter(counter -> counter.getStatus() == CounterStatus.ACTIVE
						&& counter.getBranch().getId().equals(branchId))
				.toList();
		if (capable.isEmpty()) {
			return Optional.empty();
		}
		// Contention scope: every service those counters could take instead
		// of this entry — mirroring what callNext would select first.
		List<UUID> serviceIds = capable.stream()
				.flatMap(counter -> capabilities.findByCounter(counter).stream())
				.map(capability -> capability.getService().getId())
				.distinct()
				.toList();
		List<UUID> counterIds = capable.stream().map(ServiceCounter::getId).toList();

		long nowEpoch = clock.instant().getEpochSecond();
		List<UUID> ranked = queries.waitingIdsInRankOrder(branchId, serviceIds,
				nowEpoch, aging.getIntervalMinutes(), aging.getMaxPromotions(), aging.isEnabled());
		// Position in claim order = entries that would be served first.
		// Absent (claimed concurrently after loading): conservatively count
		// everything listed — the snapshot moved under us, say so by waiting.
		int position = ranked.indexOf(entry.getId());
		long waitingAhead = position < 0 ? ranked.size() : position;
		long calledAhead = queries.countCalledInScope(branchId, serviceIds);
		long activeSessions = queries.countActiveSessions(counterIds);

		int durationMinutes = Math.toIntExact(queue.getService().getStandardDuration().toMinutes());
		long servicePosition = waitingAhead + calledAhead + activeSessions;
		int waitMinutes = (int) (servicePosition / capable.size()) * durationMinutes;
		var now = clock.instant();
		return Optional.of(new WaitTimeEstimate(entry.getId(), waitMinutes,
				now.plus(Duration.ofMinutes(waitMinutes)), now,
				(int) (waitingAhead + calledAhead), (int) activeSessions, capable.size()));
	}

	private WaitTimeEstimate immediate(QueueEntry entry) {
		var now = clock.instant();
		return new WaitTimeEstimate(entry.getId(), 0, now, now, 0, 0, 0);
	}

	/**
	 * Queue-level aggregate wait for a new joiner who has not yet entered.
	 * A new entry would rank last among all currently WAITING entries in the
	 * same capability scope. Same formula as {@link #estimateWaiting} with
	 * {@code position = ranked.size()} (the new entry is last). Whole-minute
	 * granularity; {@code estimatedWaitMinutes} may be {@code null} when the
	 * queue is inactive or has no active capable counters.
	 *
	 * @return never {@code null}, fields may have sensible defaults when the
	 *         queue cannot produce a meaningful estimate.
	 * @throws TenantContextRequiredException if no tenant context is set.
	 */
	@Transactional(readOnly = true)
	public QueueJoinEstimate estimateJoin(UUID queueId) {
		var context = TenantContextHolder.get();
		UUID orgId = context.organizationId();

		Queue queue = queues.findByIdAndOrganizationId(queueId, orgId)
				.orElseThrow(() -> new TenantContextRequiredException(
						"Queue not found or belongs to another organization"));

		List<ServiceCounter> capable = capabilities.findByService(queue.getService()).stream()
				.map(CounterCapability::getCounter)
				.filter(counter -> counter.getStatus() == CounterStatus.ACTIVE
						&& counter.getBranch().getId().equals(queue.getBranch().getId()))
				.toList();

		long nowEpoch = clock.instant().getEpochSecond();

		// Contention scope: every service those counters could take instead
		List<UUID> serviceIds = capable.stream()
				.flatMap(counter -> capabilities.findByCounter(counter).stream())
				.map(capability -> capability.getService().getId())
				.distinct()
				.toList();
		List<UUID> counterIds = capable.stream().map(ServiceCounter::getId).toList();

		int position = 0; // new entry is last among current WAITING
		List<UUID> ranked = queries.waitingIdsInRankOrder(queue.getBranch().getId(),
				serviceIds, nowEpoch, aging.getIntervalMinutes(), aging.getMaxPromotions(),
				aging.isEnabled());
		// waitingAhead for a new joiner = all currently WAITING in scope
		int waitingAhead = ranked.size();

		long calledAhead = queries.countCalledInScope(queue.getBranch().getId(), serviceIds);
		long activeSessions = queries.countActiveSessions(counterIds);

		int durationMinutes = Math.toIntExact(queue.getService().getStandardDuration().toMinutes());

		int waitMinutes;
		if (capable.isEmpty()) {
			waitMinutes = 0; // no serving capacity — explicitly zero, not silently ignored
		} else {
			int servicePosition = (int) (waitingAhead + calledAhead + activeSessions);
			waitMinutes = (int) (servicePosition / capable.size()) * durationMinutes;
		}

		// waitingCount: number of WAITING entries in this queue (scope size = ranked)
		int waitingCount = ranked.size();

		int activeSessionCount = Math.toIntExact(activeSessions);
		int capableCounterCount = capable.size();
		var calculatedAt = clock.instant();

		// No meaningful wait when queue is inactive — avoid misleading positive estimate.
		boolean queueActive = queue.getStatus() == QueueStatus.ACTIVE;
		Integer waitResult = (queueActive && !capable.isEmpty()) ? waitMinutes : null;

		return new QueueJoinEstimate(
				queueId,
				waitingCount,
				waitResult,
				capableCounterCount,
				activeSessionCount,
				calculatedAt);
	}
}
