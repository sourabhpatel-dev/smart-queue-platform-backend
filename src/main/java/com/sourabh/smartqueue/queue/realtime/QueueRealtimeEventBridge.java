package com.sourabh.smartqueue.queue.realtime;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.MimeTypeUtils;

import com.sourabh.smartqueue.infrastructure.redis.RedisJson;
import com.sourabh.smartqueue.queue.event.QueueEntryCalled;
import com.sourabh.smartqueue.queue.event.QueueEntryCancelled;
import com.sourabh.smartqueue.queue.event.QueueEntryCompleted;
import com.sourabh.smartqueue.queue.event.QueueEntryJoined;
import com.sourabh.smartqueue.queue.event.QueueEntryNoShow;
import com.sourabh.smartqueue.queue.event.QueueEntryServingStarted;
import com.sourabh.smartqueue.queue.event.ServiceSessionCompleted;
import com.sourabh.smartqueue.queue.event.ServiceSessionStarted;
import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;

/**
 * Carries committed queue facts to the real-time layer (see ADR-018).
 *
 * <p>One AFTER_COMMIT listener per consumed event: refresh the Redis
 * projection from PostgreSQL, then publish the fresh snapshot to the
 * queue's STOMP topic. Appointment events are deliberately ignored —
 * Phase 3C covers queue state only. Failures here can never roll back the
 * already-committed business transaction: they are caught, logged with the
 * queue identity (no customer data), and swallowed, so a Redis or broker
 * outage degrades live updates without touching transactional behavior.
 */
@Service
public class QueueRealtimeEventBridge {

	private static final Logger log = LoggerFactory.getLogger(QueueRealtimeEventBridge.class);

	private final QueueRealtimeProjectionService projections;
	private final SimpMessagingTemplate messaging;
	private final RedisJson json;

	public QueueRealtimeEventBridge(QueueRealtimeProjectionService projections,
			SimpMessagingTemplate messaging,
			RedisJson json) {
		this.projections = projections;
		this.messaging = messaging;
		this.json = json;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onJoined(QueueEntryJoined event) {
		refreshAndPublish(event.organizationId(), event.branchId(), event.queueId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onCalled(QueueEntryCalled event) {
		refreshAndPublish(event.organizationId(), event.branchId(), event.queueId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onServingStarted(QueueEntryServingStarted event) {
		refreshAndPublish(event.organizationId(), event.branchId(), event.queueId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onCompleted(QueueEntryCompleted event) {
		refreshAndPublish(event.organizationId(), event.branchId(), event.queueId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onCancelled(QueueEntryCancelled event) {
		refreshAndPublish(event.organizationId(), event.branchId(), event.queueId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onNoShow(QueueEntryNoShow event) {
		refreshAndPublish(event.organizationId(), event.branchId(), event.queueId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onSessionStarted(ServiceSessionStarted event) {
		refreshQueueOfEntry(event.organizationId(), event.branchId(), event.queueEntryId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onSessionCompleted(ServiceSessionCompleted event) {
		refreshQueueOfEntry(event.organizationId(), event.branchId(), event.queueEntryId());
	}

	private void refreshQueueOfEntry(UUID organizationId, UUID branchId, UUID queueEntryId) {
		if (queueEntryId == null) {
			return;
		}
		TenantContext previous = TenantContextHolder.getOrNull();
		try {
			TenantContextHolder.set(new TenantContext(organizationId, branchId));
			publishSnapshot(projections.queueOf(queueEntryId));
		} catch (RuntimeException e) {
			log.warn("real-time queue update failed for queue entry {}", queueEntryId, e);
		} finally {
			TenantContextHolder.restore(previous);
		}
	}

	private void refreshAndPublish(UUID organizationId, UUID branchId, UUID queueId) {
		if (queueId == null) {
			return;
		}
		TenantContext previous = TenantContextHolder.getOrNull();
		try {
			TenantContextHolder.set(new TenantContext(organizationId, branchId));
			publishSnapshot(queueId);
		} catch (RuntimeException e) {
			// AFTER_COMMIT: the business transaction already committed, so
			// this must never propagate back into it. Live updates degrade;
			// PostgreSQL stays authoritative and the snapshot rebuilds later.
			log.warn("real-time queue update failed for queue {}", queueId, e);
		} finally {
			TenantContextHolder.restore(previous);
		}
	}

	private void publishSnapshot(UUID queueId) {
		QueueRealtimeState snapshot = projections.refresh(queueId);
		String destination = QueueRealtimeDestinations.topic(snapshot.organizationId(),
				snapshot.branchId(), snapshot.queueId());
		messaging.convertAndSend(destination, json.toJson(snapshot),
				Map.of(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON));
	}
}
