package com.sourabh.smartqueue.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * After-commit email delivery helper (see the email decision
 * notes). Every lifecycle service sends through here: the message
 * is built inside the business transaction (so the token row and
 * the email agree), but delivery runs only after that transaction
 * commits. Transport failure degrades to a warning log — the
 * persisted row is the source of truth, resend endpoints exist for
 * every email type, and SMTP trouble can never roll back account
 * state. Same shape as the in-app notification echo (ADR-032).
 */
public final class EmailDispatch {

	private static final Logger log = LoggerFactory.getLogger(EmailDispatch.class);

	private EmailDispatch() {
	}

	public static void afterCommit(EmailSender sender, EmailMessage message) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					sender.send(message);
				} catch (EmailDeliveryException e) {
					log.warn("lifecycle email delivery failed: to={} subject={}",
							message.to(), message.subject(), e);
				}
			}
		});
	}
}
