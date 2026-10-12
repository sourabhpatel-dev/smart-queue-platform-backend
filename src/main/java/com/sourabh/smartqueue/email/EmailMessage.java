package com.sourabh.smartqueue.email;

/**
 * One outbound account-lifecycle email (see the email decision
 * notes). Plain-text only — no HTML templates, no tracking, no
 * attachments in this phase. The body may carry single-use links;
 * that is exactly why it must never be logged (see
 * {@link LogEmailSender}).
 */
public record EmailMessage(String to, String subject, String bodyText) {

	public EmailMessage {
		if (to == null || to.isBlank()) {
			throw new IllegalArgumentException("recipient is required");
		}
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("subject is required");
		}
		if (bodyText == null || bodyText.isBlank()) {
			throw new IllegalArgumentException("body is required");
		}
	}
}
