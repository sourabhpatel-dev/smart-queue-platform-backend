package com.sourabh.smartqueue.queue;

import java.time.Clock;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.counter.CounterCapabilityRepository;
import com.sourabh.smartqueue.counter.CounterStatus;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.queue.event.QueueEntryCancelled;
import com.sourabh.smartqueue.queue.event.QueueEntryCompleted;
import com.sourabh.smartqueue.queue.event.QueueEntryNoShow;
import com.sourabh.smartqueue.queue.event.QueueEntryServingStarted;
import com.sourabh.smartqueue.queue.event.ServiceSessionCompleted;
import com.sourabh.smartqueue.queue.event.ServiceSessionStarted;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.tenant.TenantContextRequiredException;

/**
 * Post-call entry transitions, now coordinating the service session
 * (see ADR-013).
 *
 * <p>{@code startServing} and {@code complete} each lock the entry row
 * first ({@code SELECT … FOR UPDATE}): a concurrent second caller blocks,
 * then observes the first caller's committed state and fails its guard
 * instead of racing it. The entry transition and the session write commit
 * together — {@code SERVING} without a session (or {@code COMPLETED} with
 * an open one) can never persist.
 *
 * <p>No requeue behavior: what happens after a no-show is a later business
 * rule, not a silent state shuffle here.
 */
@Service
public class QueueEntryLifecycleService {

	private final QueueEntryRepository entries;
	private final ServiceSessionRepository sessions;
	private final ServiceCounterRepository counters;
	private final CounterCapabilityRepository capabilities;
	private final Clock clock;
	private final ApplicationEventPublisher events;

	public QueueEntryLifecycleService(QueueEntryRepository entries,
			ServiceSessionRepository sessions,
			ServiceCounterRepository counters,
			CounterCapabilityRepository capabilities,
			Clock clock,
			ApplicationEventPublisher events) {
		this.entries = entries;
		this.sessions = sessions;
		this.counters = counters;
		this.capabilities = capabilities;
		this.clock = clock;
		this.events = events;
	}

	/**
	 * Starts service: creates the session and moves
	 * {@code CALLED → SERVING} atomically.
	 *
	 * @throws QueueEntryNotFoundException for an unknown entry.
	 * @throws InvalidQueueRequestException for an unknown counter.
	 * @throws CounterCannotServeQueueException for an inactive counter, a
	 * cross-branch counter, or a counter lacking the entry's service.
	 * @throws QueueEntryStateException unless the entry is CALLED.
	 * @throws QueueConflictException if a session already exists.
	 */
	@Transactional
	public QueueEntry startServing(UUID entryId, UUID counterId) {
		var context = TenantContextHolder.get();
		QueueEntry entry = loadLockedInScope(entryId, context.organizationId());
		if (entry.getStatus() != QueueEntryStatus.CALLED) {
			throw new QueueEntryStateException(
					"only CALLED entries can start serving (was " + entry.getStatus() + ")");
		}
		ServiceCounter counter = counters.findById(counterId)
				.orElseThrow(() -> new InvalidQueueRequestException("unknown counter: " + counterId));
		if (counter.getStatus() != CounterStatus.ACTIVE) {
			throw new CounterCannotServeQueueException("counter is not active: " + counterId);
		}
		if (!counter.getBranch().getId().equals(entry.getBranch().getId())) {
			throw new CounterCannotServeQueueException(
					"counter and entry belong to different branches");
		}
		boolean capable = capabilities.findByCounter(counter).stream()
				.anyMatch(capability -> capability.getService().getId()
						.equals(entry.getQueue().getService().getId()));
		if (!capable) {
			throw new CounterCannotServeQueueException(
					"counter does not support the entry's service");
		}
		if (sessions.findByQueueEntryId(entryId).isPresent()) {
			throw new QueueConflictException("a session already exists for this entry");
		}

		ServiceSession session = sessions.saveAndFlush(new ServiceSession(entry.getBranch(),
				entry, counter, entry.getQueue().getService(), clock.instant()));
		entry.startServing();
		QueueEntry saved = entries.saveAndFlush(entry);
		events.publishEvent(new QueueEntryServingStarted(UUID.randomUUID(), clock.instant(),
				saved.getOrganization().getId(), saved.getBranch().getId(),
				saved.getQueue().getId(), saved.getId(),
				saved.getQueue().getService().getId(), saved.getCustomer().getId(),
				counter.getId()));
		events.publishEvent(new ServiceSessionStarted(UUID.randomUUID(), clock.instant(),
				saved.getOrganization().getId(), saved.getBranch().getId(),
				saved.getId(), session.getId(), counter.getId(),
				saved.getQueue().getService().getId(), saved.getCustomer().getId(),
				session.getStartedAt()));
		return saved;
	}

	/**
	 * Completes service: ends the session and moves
	 * {@code SERVING → COMPLETED} atomically.
	 *
	 * @throws QueueEntryNotFoundException for an unknown entry or an entry
	 * outside the current tenant scope.
	 * @throws QueueEntryStateException unless the entry is SERVING.
	 * @throws QueueConflictException if no open session exists.
	 * @throws TenantContextRequiredException if no tenant context is set.
	 */
	@Transactional
	public QueueEntry complete(UUID entryId) {
		var context = TenantContextHolder.get();
		QueueEntry entry = loadLockedInScope(entryId, context.organizationId());
		if (entry.getStatus() != QueueEntryStatus.SERVING) {
			throw new QueueEntryStateException(
					"only SERVING entries can complete (was " + entry.getStatus() + ")");
		}
		ServiceSession session = sessions.findByQueueEntryId(entryId)
				.orElseThrow(() -> new QueueConflictException(
						"no session exists for this entry"));
		if (!session.isActive()) {
			throw new QueueConflictException("session is already ended");
		}
		session.end(clock.instant());
		entry.complete();
		QueueEntry saved = entries.saveAndFlush(entry);
		events.publishEvent(new QueueEntryCompleted(UUID.randomUUID(), clock.instant(),
				saved.getOrganization().getId(), saved.getBranch().getId(),
				saved.getQueue().getId(), saved.getId(),
				saved.getQueue().getService().getId(), saved.getCustomer().getId()));
		events.publishEvent(new ServiceSessionCompleted(UUID.randomUUID(), clock.instant(),
				saved.getOrganization().getId(), saved.getBranch().getId(),
				saved.getId(), session.getId(), session.getCounter().getId(),
				saved.getQueue().getService().getId(), saved.getCustomer().getId(),
				session.getStartedAt(), session.getEndedAt()));
		return saved;
	}

	/**
	 * @throws QueueEntryNotFoundException for an unknown id or an entry
	 * outside the current tenant scope.
	 * @throws QueueEntryStateException unless the entry is WAITING or CALLED.
	 * @throws TenantContextRequiredException if no tenant context is set.
	 */
	@Transactional
	public QueueEntry cancel(UUID entryId) {
		var context = TenantContextHolder.get();
		QueueEntry entry = loadInScope(entryId, context.organizationId());
		entry.cancel();
		QueueEntry saved = entries.saveAndFlush(entry);
		events.publishEvent(new QueueEntryCancelled(UUID.randomUUID(), clock.instant(),
				saved.getOrganization().getId(), saved.getBranch().getId(),
				saved.getQueue().getId(), saved.getId(),
				saved.getQueue().getService().getId(), saved.getCustomer().getId()));
		return saved;
	}

	/**
	 * @throws QueueEntryNotFoundException for an unknown id or an entry
	 * outside the current tenant scope.
	 * @throws QueueEntryStateException unless the entry is WAITING or CALLED.
	 * @throws TenantContextRequiredException if no tenant context is set.
	 */
	@Transactional
	public QueueEntry markNoShow(UUID entryId) {
		var context = TenantContextHolder.get();
		QueueEntry entry = loadInScope(entryId, context.organizationId());
		entry.markNoShow();
		QueueEntry saved = entries.saveAndFlush(entry);
		events.publishEvent(new QueueEntryNoShow(UUID.randomUUID(), clock.instant(),
				saved.getOrganization().getId(), saved.getBranch().getId(),
				saved.getQueue().getId(), saved.getId(),
				saved.getQueue().getService().getId(), saved.getCustomer().getId()));
		return saved;
	}

	private QueueEntry loadInScope(UUID entryId, UUID organizationId) {
		return entries.findByIdAndOrganizationId(entryId, organizationId)
				.orElseThrow(() -> new QueueEntryNotFoundException(
						"unknown queue entry: " + entryId));
	}

	private QueueEntry loadLockedInScope(UUID entryId, UUID organizationId) {
		return entries.findLockedById(entryId)
				.filter(entry -> entry.getOrganization().getId().equals(organizationId))
				.orElseThrow(() -> new QueueEntryNotFoundException(
						"unknown queue entry: " + entryId));
	}
}
