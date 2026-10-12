package com.sourabh.smartqueue.email;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Selects the single {@link EmailSender} (see the email decision
 * notes).
 *
 * <p>The two candidates are keyed on opposite values of the same
 * property, so exactly one can match — never zero, never two.
 * {@code @ConditionalOnMissingBean} is deliberately NOT used here: on
 * sibling {@code @Bean} methods it evaluates in declaration order, so
 * the log sender (declared first) also matches when {@code smtp} is
 * configured, yielding two senders and a startup injection failure
 * (proven by {@code SmtpEmailSenderTests} before this fix). The
 * {@code log} branch carries {@code matchIfMissing} to preserve the
 * development default.
 */
@Configuration
public class EmailSenderConfiguration {

	@Bean
	@ConditionalOnProperty(prefix = "app.email", name = "provider",
			havingValue = "log", matchIfMissing = true)
	EmailSender logEmailSender(Clock clock) {
		return new LogEmailSender(clock);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.email", name = "provider", havingValue = "smtp")
	EmailSender smtpEmailSender(JavaMailSender mailSender, EmailProperties properties) {
		return new SmtpEmailSender(mailSender, properties);
	}
}
