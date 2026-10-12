package com.sourabh.smartqueue.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * SMTP email sender (see the email decision notes).
 *
 * <p>Active only when {@code app.email.provider=smtp} (wired by
 * {@link EmailSenderConfiguration}, which itself requires a host —
 * both fail fast otherwise). Sends plain-text messages through the
 * configured relay. Transport failures become
 * {@link EmailDeliveryException} carrying recipient + subject only
 * (never the body, never provider internals for clients — callers
 * log and continue; every email type has a resend path).
 */
public class SmtpEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

	private final JavaMailSender mailSender;
	private final EmailProperties properties;

	public SmtpEmailSender(JavaMailSender mailSender, EmailProperties properties) {
		this.mailSender = mailSender;
		this.properties = properties;
	}

	@Override
	public void send(EmailMessage message) {
		SimpleMailMessage mail = new SimpleMailMessage();
		mail.setFrom(properties.getFromAddress());
		mail.setTo(message.to());
		mail.setSubject(message.subject());
		mail.setText(message.bodyText());
		try {
			mailSender.send(mail);
		} catch (MailException | IllegalArgumentException e) {
			log.warn("email delivery failed: to={} subject={}", message.to(), message.subject(), e);
			throw new EmailDeliveryException(
					"email delivery failed for " + message.to(), e);
		}
	}
}
