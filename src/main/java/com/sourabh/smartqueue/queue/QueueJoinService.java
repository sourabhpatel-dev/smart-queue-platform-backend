package com.sourabh.smartqueue.queue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.branch.BranchStatus;
import com.sourabh.smartqueue.queue.event.QueueEntryJoined;
import com.sourabh.smartqueue.service.ServiceStatus;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.user.UserStatus;

/**
 * The walk-in use case: a customer joins a queue and receives a token
 * (see ADR-012).
 *
 * <p>One transaction: validate queue/branch/service/customer, derive the
 * branch-local business date, allocate the token via
 * {@link QueueTokenAllocator}, persist. Token allocation never reads
 * {@code MAX()+1}: the day's counter row is ensured conflict-free, then
 * concurrent joins serialize on its row lock. Residual races (duplicate
 * active entry, duplicate token) translate to {@link QueueConflictException}.
 */
@Service
public class QueueJoinService {

	private final QueueRepository queues;
	private final UserRepository users;
	private final QueueEntryRepository entries;
	private final QueueTokenAllocator tokens;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	public QueueJoinService(QueueRepository queues,
			UserRepository users,
			QueueEntryRepository entries,
			QueueTokenAllocator tokens,
			ApplicationEventPublisher events,
			Clock clock) {
		this.queues = queues;
		this.users = users;
		this.entries = entries;
		this.tokens = tokens;
		this.events = events;
		this.clock = clock;
	}

	/**
	 * @throws InvalidQueueRequestException for unknown ids, inactive
	 * branch/service/customer, or cross-organization mismatch.
	 * @throws QueueNotActiveException if the queue itself is INACTIVE.
	 * @throws QueueConflictException if the customer already holds an active
	 * entry (or a token race is lost after retries).
	 */
	@Transactional
	public QueueEntry join(UUID queueId, UUID customerId, QueuePriority priority) {
		Queue queue = queues.findById(queueId)
				.orElseThrow(() -> new InvalidQueueRequestException("unknown queue: " + queueId));
		User customer = users.findById(customerId)
				.orElseThrow(() -> new InvalidQueueRequestException("unknown customer: " + customerId));

		// ADR-041: only ACTIVE organizations accept queue joins — the
		// same domain backstop as booking (service-layer scope denies
		// first for customers; this holds for every direct caller).
		if (queue.getOrganization().getStatus()
				!= com.sourabh.smartqueue.organization.OrganizationStatus.ACTIVE) {
			throw new InvalidQueueRequestException(
					"organization is not active: " + queue.getOrganization().getId());
		}
		if (queue.getStatus() != QueueStatus.ACTIVE) {
			throw new QueueNotActiveException("queue is not active: " + queueId);
		}
		if (queue.getBranch().getStatus() != BranchStatus.ACTIVE) {
			throw new InvalidQueueRequestException(
					"branch is not active: " + queue.getBranch().getId());
		}
		if (queue.getService().getStatus() != ServiceStatus.ACTIVE) {
			throw new InvalidQueueRequestException(
					"service is not active: " + queue.getService().getId());
		}
		if (customer.getStatus() != UserStatus.ACTIVE) {
			throw new InvalidQueueRequestException(
					"customer is not active: " + customerId);
		}
		// ADR-040: no customer↔organization equality — customers (and
		// legacy organization values that never proved membership) join
		// any ACTIVE queue; the queue's organization is the row's
		// historical context. The service layer already validated the
		// queue/branch scope; this backstop applies only to operational
		// principals joining within their own organization.
		boolean customerIsOperational = customer.getRoles().stream()
				.anyMatch(role -> role != RoleType.CUSTOMER);
		if (customerIsOperational && customer.getOrganization() != null
				&& !customer.getOrganization().getId().equals(queue.getOrganization().getId())) {
			throw new InvalidQueueRequestException(
					"customer and queue belong to different organizations");
		}

		ZoneId zone = queue.getBranch().getTimeZone();
		LocalDate businessDate = Instant.now().atZone(zone).toLocalDate();
		int tokenNumber = tokens.allocate(queue.getId(), businessDate);

		QueueEntry entry = new QueueEntry(queue.getOrganization(), queue, customer,
				tokenNumber, businessDate, priority);
		try {
			QueueEntry saved = entries.saveAndFlush(entry);
			// Published after the state is established; AFTER_COMMIT consumers
			// (the only kind this phase defines) see it solely on commit.
			events.publishEvent(new QueueEntryJoined(UUID.randomUUID(), clock.instant(),
					queue.getOrganization().getId(), queue.getBranch().getId(),
					queue.getId(), saved.getId(), queue.getService().getId(),
					customer.getId(), businessDate, tokenNumber, priority));
			return saved;
		} catch (DataIntegrityViolationException e) {
			throw new QueueConflictException(
					"customer already holds an active entry in this queue", e);
		}
	}
}

