package com.sourabh.smartqueue.auth;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueRepository;

/**
 * STOMP subscription authorization (see ADR-021).
 *
 * <p>Authentication already happened at the WebSocket handshake
 * ({@link AuthHandshakeInterceptor} + {@link PrincipalHandshakeHandler}),
 * so every message here carries the session user. This interceptor
 * authorizes what that user may <em>see</em>:
 *
 * <ol>
 *   <li><strong>SUBSCRIBE / SEND</strong> to queue destinations must stay
 *   within the principal's scope: same organization, branch access per
 *   {@link TenantAccessPolicy}, and (for queue paths) the queue actually
 *   living at the requested scope. Unknown destinations are denied —
 *   fail closed.</li>
 * </ol>
 *
 * <p>Key namespacing is not authorization: the destination string selects
 * the scope under test, the database plus the policy decide. A denial
 * sends a STOMP ERROR and terminates the session — clients reconnect and
 * subscribe within scope.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

	private static final Logger log =
			LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

	private final TenantAccessPolicy accessPolicy;
	private final QueueRepository queues;

	public StompAuthChannelInterceptor(TenantAccessPolicy accessPolicy,
			QueueRepository queues) {
		this.accessPolicy = accessPolicy;
		this.queues = queues;
	}

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
		if (accessor.getCommand() == null) {
			return message;
		}
		if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
			authorizeTopicSubscription(message, principalOf(message, accessor), accessor.getDestination());
			return message;
		}
		if (accessor.getCommand() == StompCommand.SEND) {
			authorizeAppDestination(message, principalOf(message, accessor), accessor.getDestination());
			return message;
		}
		return message;
	}

	private AuthenticatedPrincipal principalOf(Message<?> message, StompHeaderAccessor accessor) {
		if (accessor.getUser() instanceof Authentication authentication
				&& authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
			return principal;
		}
		throw new MessageDeliveryException(message, "unauthenticated STOMP session");
	}

	private void authorizeTopicSubscription(Message<?> message,
			AuthenticatedPrincipal principal, String destination) {
		// The fixed per-session notification destination (see ADR-032):
		// Spring delivers only the subscribing session's own rows here,
		// so the authenticated session itself is the recipient scope —
		// no id travels in a subscribable path, nothing can be spoofed.
		if (com.sourabh.smartqueue.notification.NotificationDestinations.USER_DESTINATION
				.equals(destination)) {
			return;
		}
		QueueScope scope = parseTopicDestination(message, destination);
		authorizeQueueScope(message, principal, scope);
	}

	private void authorizeAppDestination(Message<?> message,
			AuthenticatedPrincipal principal, String destination) {
		QueueScope scope = parseSnapshotDestination(message, destination);
		authorizeQueueScope(message, principal, scope);
	}

	private void authorizeQueueScope(Message<?> message,
			AuthenticatedPrincipal principal, QueueScope scope) {
		Queue queue = queues.findById(scope.queueId())
				.orElseThrow(() -> new MessageDeliveryException(message, "unknown queue scope"));
		try {
			accessPolicy.requireQueueScopeAccess(principal,
					scope.organizationId(), scope.branchId(), queue);
		} catch (RuntimeException e) {
			log.debug("STOMP scope denied for user {}: {}", principal.userId(), e.getMessage());
			throw new MessageDeliveryException(message, "insufficient scope authorization");
		}
	}

	private record QueueScope(UUID organizationId, UUID branchId, UUID queueId) {
	}

	private static QueueScope parseTopicDestination(Message<?> message, String destination) {
		String[] parts = split(destination);
		if (parts.length == 8
				&& parts[1].equals("topic")
				&& parts[2].equals("organizations")
				&& parts[4].equals("branches")
				&& parts[6].equals("queues")) {
			return ids(message, parts[3], parts[5], parts[7]);
		}
		throw new MessageDeliveryException(message, "unknown subscription destination");
	}

	private static QueueScope parseSnapshotDestination(Message<?> message, String destination) {
		String[] parts = split(destination);
		if (parts.length == 9
				&& parts[1].equals("app")
				&& parts[2].equals("organizations")
				&& parts[4].equals("branches")
				&& parts[6].equals("queues")
				&& parts[8].equals("snapshot")) {
			return ids(message, parts[3], parts[5], parts[7]);
		}
		throw new MessageDeliveryException(message, "unknown application destination");
	}

	private static String[] split(String destination) {
		if (destination == null) {
			return new String[0];
		}
		return destination.split("/", -1);
	}

	private static QueueScope ids(Message<?> message, String organization, String branch, String queue) {
		try {
			return new QueueScope(
					UUID.fromString(organization), UUID.fromString(branch), UUID.fromString(queue));
		} catch (IllegalArgumentException e) {
			throw new MessageDeliveryException(message, "malformed scope destination");
		}
	}
}
