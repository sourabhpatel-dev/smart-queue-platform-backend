package com.sourabh.smartqueue.notification;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import com.sourabh.smartqueue.infrastructure.redis.RedisJson;
import com.sourabh.smartqueue.notification.api.NotificationResponse;

/**
 * In-app realtime delivery over the existing STOMP broker (see ADR-032).
 *
 * <p>Sends the persisted notification to the recipient's user
 * destination ({@code /user/queue/notifications}, resolved per session
 * from the handshake-bound principal name — the user id). No user id
 * travels in any destination the client can influence: the client
 * subscribes to the fixed {@code /user} path and Spring delivers only
 * that session's rows. Payloads are the same small DTO the REST API
 * returns — ids and copy only, never entities, credentials, or tokens.
 */
@Service
public class InAppNotificationSender implements NotificationSender {

	private static final Logger log = LoggerFactory.getLogger(InAppNotificationSender.class);

	private final SimpMessagingTemplate messaging;
	private final RedisJson json;

	public InAppNotificationSender(SimpMessagingTemplate messaging, RedisJson json) {
		this.messaging = messaging;
		this.json = json;
	}

	@Override
	public void send(Notification notification) {
		UUID recipientId = notification.getRecipientUser().getId();
		String payload = json.toJson(NotificationResponse.from(notification));
		messaging.convertAndSendToUser(recipientId.toString(),
				NotificationDestinations.USER_QUEUE,
				payload,
				Map.of(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON));
		log.debug("delivered in-app notification {} to user {}", notification.getId(), recipientId);
	}
}
