package com.sourabh.smartqueue.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development default email sender (see the email decision notes).
 *
 * <p>Active whenever no SMTP provider is configured (wired by
 * {@link EmailSenderConfiguration}). Records the delivery attempt
 * (recipient + subject + instant) and sends nothing. The message
 * body is NEVER logged — it carries single-use tokens. Local
 * developers needing real delivery run the documented Mailpit relay
 * (see {@code compose.yaml}) instead of switching this
 * implementation.
 */
public class LogEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

	private final java.time.Clock clock;

	public LogEmailSender(java.time.Clock clock) {
		this.clock = clock;
	}

	@Override
	public void send(EmailMessage message) {
		log.info("email delivery skipped (log provider): to={} subject={} at={}",
				message.to(), message.subject(), clock.instant());
	}
}
