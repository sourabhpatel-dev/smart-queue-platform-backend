package com.sourabh.smartqueue.queue;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.counter.CounterCapabilityRepository;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.counter.CounterStatus;
import com.sourabh.smartqueue.queue.event.QueueEntryCalled;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.tenant.TenantContextRequiredException;

/**
 * The "call next" use case: a counter claims its next waiting customer
 * (see ADR-012).
 *
 * <p>One transaction: load and vet the counter, resolve the services it may
 * serve from its capabilities, then atomically claim the highest-ranked
 * WAITING entry across those services in this branch
 * ({@code FOR UPDATE SKIP LOCKED} — a concurrently claimed row is skipped,
 * not waited on, so a second counter takes the next eligible entry instead
 * of blocking). Ranking is base priority plus capped aging at the project
 * Clock's now (see ADR-014 and {@link QueueAgingProperties}). The claim and
 * the {@code WAITING → CALLED} transition are the same atomic unit: a
 * claimed row can never be handed to two callers.
 */
@Service
public class QueueCallService {

	private final ServiceCounterRepository counters;
	private final CounterCapabilityRepository capabilities;
	private final QueueEntryClaimRepository claims;
	private final QueueEntryRepository entries;
	private final Clock clock;
	private final QueueAgingProperties aging;
	private final ApplicationEventPublisher events;

	public QueueCallService(ServiceCounterRepository counters,
			CounterCapabilityRepository capabilities,
			QueueEntryClaimRepository claims,
			QueueEntryRepository entries,
			Clock clock,
			QueueAgingProperties aging,
			ApplicationEventPublisher events) {
		this.counters = counters;
		this.capabilities = capabilities;
		this.claims = claims;
		this.entries = entries;
		this.clock = clock;
		this.aging = aging;
		this.events = events;
	}

	/**
	 * @throws InvalidQueueRequestException for an unknown counter or a
	 * counter outside the current tenant scope.
	 * @throws CounterCannotServeQueueException if the counter is not ACTIVE
	 * or supports no services at all.
	 * @throws NoWaitingQueueEntryException if nothing eligible is waiting
	 * for the services this counter serves.
	 * @throws TenantContextRequiredException if no tenant context is set.
	 */
	@Transactional
	public QueueEntry callNext(UUID counterId) {
		var context = TenantContextHolder.get();
		ServiceCounter counter = counters.findByIdAndBranchId(counterId, context.branchId())
				.orElseThrow(() -> new InvalidQueueRequestException("unknown counter: " + counterId));
		if (counter.getStatus() != CounterStatus.ACTIVE) {
			throw new CounterCannotServeQueueException(
					"counter is not active: " + counterId);
		}
		List<UUID> serviceIds = capabilities.findByCounter(counter).stream()
				.map(capability -> capability.getService().getId())
				.toList();
		if (serviceIds.isEmpty()) {
			throw new CounterCannotServeQueueException(
					"counter supports no services: " + counterId);
		}

		Optional<QueueEntry> claimed = claims.claimNext(counter.getBranch().getId(), serviceIds,
				clock.instant().getEpochSecond(), aging);
		QueueEntry entry = claimed.orElseThrow(() -> new NoWaitingQueueEntryException(
				"no waiting entries for the services served by counter " + counterId));
		entry.call();
		QueueEntry saved = entries.saveAndFlush(entry);
		events.publishEvent(new QueueEntryCalled(UUID.randomUUID(), clock.instant(),
				saved.getOrganization().getId(), counter.getBranch().getId(),
				saved.getQueue().getId(), saved.getId(),
				saved.getQueue().getService().getId(), saved.getCustomer().getId(),
				counter.getId()));
		return saved;
	}
}
