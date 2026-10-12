package com.sourabh.smartqueue.queue.realtime;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.util.MimeTypeUtils;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.TenantAccessPolicy;
import com.sourabh.smartqueue.infrastructure.redis.RedisJson;
import com.sourabh.smartqueue.queue.InvalidQueueRequestException;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;

/**
 * On-demand snapshot requests over STOMP (see ADR-018, authorized in ADR-021).
 *
 * <p>A newly subscribed client needs current state <em>now</em>, not at the
 * next business event: it SENDs to the snapshot destination, the server
 * rebuilds the projection from PostgreSQL (recreating a missing or expired
 * Redis value as a side effect) and publishes the fresh snapshot to the
 * queue topic the client is subscribed to.
 *
 * <p>Two authorization layers, both required: the inbound channel
 * interceptor already authenticated the session and scoped the SEND, and
 * this handler additionally verifies that the path organization matches
 * the principal's organization <em>before</em> the failure-swallowing
 * broadcast block — a scope mismatch is rethrown, never merely logged.
 * Path variables are verified against the queue's actual tenant scope —
 * a request naming the wrong organization/branch is rejected rather than
 * served.
 */
@Controller
public class QueueRealtimeController {

	private static final Logger log =
			LoggerFactory.getLogger(QueueRealtimeController.class);

	private final QueueRepository queues;
	private final QueueRealtimeProjectionService projections;
	private final SimpMessagingTemplate messaging;
	private final RedisJson json;
	private final TenantAccessPolicy access;

	public QueueRealtimeController(QueueRepository queues,
			QueueRealtimeProjectionService projections,
			SimpMessagingTemplate messaging,
			RedisJson json,
			TenantAccessPolicy access) {
		this.queues = queues;
		this.projections = projections;
		this.messaging = messaging;
		this.json = json;
		this.access = access;
	}

	@MessageMapping("/organizations/{organizationId}/branches/{branchId}/queues/{queueId}/snapshot")
	public void snapshot(
			@DestinationVariable UUID organizationId,
			@DestinationVariable UUID branchId,
			@DestinationVariable UUID queueId,
			Principal principal) {
		Queue queue = queues.findById(queueId)
				.orElseThrow(() -> new InvalidQueueRequestException("unknown queue: " + queueId));
		if (!queue.getOrganization().getId().equals(organizationId)
				|| !queue.getBranch().getId().equals(branchId)) {
			throw new InvalidQueueRequestException(
					"queue does not belong to the requested organization/branch");
		}
		if (principal instanceof Authentication authentication
				&& authentication.getPrincipal() instanceof AuthenticatedPrincipal authenticated) {
			// Same rule the channel interceptor enforces on the SEND
			// itself (organization, queue-at-scope, branch role — or, for
			// platform-level customers, an ACTIVE queue at the requested
			// scope): a second layer here so the handler never depends on
			// interceptor ordering alone. Deliberately before the
			// broadcast try/catch — denials propagate, never merely log.
			access.requireQueueScopeAccess(authenticated, organizationId, branchId, queue);
		}
		TenantContext previous = TenantContextHolder.getOrNull();
		try {
			TenantContextHolder.set(new TenantContext(organizationId, branchId));
			QueueRealtimeState snapshot = projections.refresh(queueId);
			messaging.convertAndSend(
					QueueRealtimeDestinations.topic(organizationId, branchId, queueId),
					json.toJson(snapshot),
					Map.of(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON));
		} catch (RuntimeException e) {
			log.warn("on-demand queue snapshot failed for queue {}", queueId, e);
		} finally {
			TenantContextHolder.restore(previous);
		}
	}
}

