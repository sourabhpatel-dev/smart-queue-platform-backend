package com.sourabh.smartqueue.notification;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sourabh.smartqueue.appointment.event.AppointmentBooked;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.counter.ServiceCounterRepository;
import com.sourabh.smartqueue.notification.application.NotificationService;
import com.sourabh.smartqueue.queue.event.QueueEntryCalled;
import com.sourabh.smartqueue.queue.event.QueueEntryCancelled;
import com.sourabh.smartqueue.queue.event.QueueEntryCompleted;
import com.sourabh.smartqueue.queue.event.QueueEntryJoined;
import com.sourabh.smartqueue.queue.event.QueueEntryNoShow;
import com.sourabh.smartqueue.service.ServiceRepository;

/**
 * Turns committed domain facts into customer notifications (see ADR-032).
 *
 * <p>One AFTER_COMMIT listener per notified event — rollback therefore
 * never notifies (proven by test), and the business transaction never
 * waits on notification work. Recipient selection is deliberately
 * narrow: the event's own {@code customerId}, verified against the
 * event's organization before anything persists. Events without a safe
 * recipient produce nothing here:
 *
 * <ul>
 *   <li>Serving-started/session events carry no new customer meaning
 *   beyond the CALLED notification, so they stay silent.</li>
 *   <li>Staff broadcasts are omitted — no event names a safe staff
 *   recipient, and notifying every assigned staff member would be spam
 *   by design.</li>
 *   <li>Appointment cancel/complete/no-show flows publish no events
 *   today, so they have no notifications (documented limitation).</li>
 * </ul>
 *
 * <p>Display names (branch/service/counter) are loaded read-only for
 * copy; when a lookup fails the notification still persists with a
 * generic message — copy enrichment must never block the fact.
 */
@Service
public class NotificationEventListener {

	private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

	private final NotificationService notifications;
	private final BranchRepository branches;
	private final ServiceRepository services;
	private final ServiceCounterRepository counters;

	public NotificationEventListener(NotificationService notifications,
			BranchRepository branches,
			ServiceRepository services,
			ServiceCounterRepository counters) {
		this.notifications = notifications;
		this.branches = branches;
		this.services = services;
		this.counters = counters;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onAppointmentBooked(AppointmentBooked event) {
		notify(event.organizationId(), event.customerId(), NotificationType.APPOINTMENT_BOOKED,
				"Appointment booked",
				"Your appointment at " + branchName(event.organizationId(), event.branchId())
						+ " is booked for " + event.startAt() + ".",
				NotificationReferenceType.APPOINTMENT, event.appointmentId(), event.eventId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onQueueJoined(QueueEntryJoined event) {
		notify(event.organizationId(), event.customerId(), NotificationType.QUEUE_JOINED,
				"You joined the queue",
				"Token " + event.tokenNumber() + " at " + branchName(event.organizationId(), event.branchId())
						+ " (" + serviceName(event.organizationId(), event.serviceId()) + ").",
				NotificationReferenceType.QUEUE_ENTRY, event.queueEntryId(), event.eventId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onQueueCalled(QueueEntryCalled event) {
		notify(event.organizationId(), event.customerId(), NotificationType.QUEUE_CALLED,
				"Your number was called",
				"Please proceed to " + counterName(event.organizationId(), event.counterId())
						+ " at " + branchName(event.organizationId(), event.branchId()) + ".",
				NotificationReferenceType.QUEUE_ENTRY, event.queueEntryId(), event.eventId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onQueueCompleted(QueueEntryCompleted event) {
		notify(event.organizationId(), event.customerId(), NotificationType.QUEUE_COMPLETED,
				"Service completed",
				"Your visit at " + branchName(event.organizationId(), event.branchId())
						+ " is complete. Thank you!",
				NotificationReferenceType.QUEUE_ENTRY, event.queueEntryId(), event.eventId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onQueueCancelled(QueueEntryCancelled event) {
		notify(event.organizationId(), event.customerId(), NotificationType.QUEUE_CANCELLED,
				"Queue entry cancelled",
				"Your queue entry at " + branchName(event.organizationId(), event.branchId())
						+ " was cancelled.",
				NotificationReferenceType.QUEUE_ENTRY, event.queueEntryId(), event.eventId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onQueueNoShow(QueueEntryNoShow event) {
		notify(event.organizationId(), event.customerId(), NotificationType.QUEUE_NO_SHOW,
				"Marked as no-show",
				"You were marked as no-show at " + branchName(event.organizationId(), event.branchId())
						+ ". Please rejoin if you still need service.",
				NotificationReferenceType.QUEUE_ENTRY, event.queueEntryId(), event.eventId());
	}

	private void notify(UUID organizationId, UUID customerId, NotificationType type,
			String title, String message, NotificationReferenceType referenceType,
			UUID referenceId, UUID eventId) {
		try {
			notifications.create(organizationId, customerId, type, title, message,
					referenceType, referenceId, eventId);
		} catch (RuntimeException e) {
			// AFTER_COMMIT: the business fact already committed. A
			// notification failure degrades the center's freshness for one
			// row — it must never propagate back into the committed flow.
			log.warn("notification creation failed for event {}", eventId, e);
		}
	}

	private String branchName(UUID organizationId, UUID branchId) {
		return branches.findById(branchId)
				.filter(branch -> branch.getOrganization().getId().equals(organizationId))
				.map(branch -> branch.getName())
				.orElse("your branch");
	}

	private String serviceName(UUID organizationId, UUID serviceId) {
		return services.findById(serviceId)
				.filter(service -> service.getBranch().getOrganization().getId().equals(organizationId))
				.map(service -> service.getName())
				.orElse("your service");
	}

	private String counterName(UUID organizationId, UUID counterId) {
		if (counterId == null) {
			return "the counter";
		}
		return counters.findById(counterId)
				.filter(counter -> counter.getBranch().getOrganization().getId().equals(organizationId))
				.map(counter -> counter.getName())
				.orElse("the counter");
	}
}
