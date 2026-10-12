package com.sourabh.smartqueue.email;

import java.util.Properties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * SMTP relay wiring (see the email decision notes).
 *
 * <p>Builds the single {@link JavaMailSender} from
 * {@code app.email.smtp.*} — one config surface, no split with
 * {@code spring.mail.*}. Only active when
 * {@code app.email.provider=smtp} (which itself requires a host, so
 * a half-configured relay fails fast here with a clear message,
 * not deep inside a send call). Authentication is enabled only when
 * a username is set (local relays like Mailpit need none); STARTTLS
 * is enabled only when {@code starttls-enabled} is set (local
 * relays speak plain SMTP; real external relays require it —
 * see the local-development email docs for both shapes).
 */
@Configuration
@ConditionalOnProperty(prefix = "app.email", name = "provider", havingValue = "smtp")
public class EmailConfiguration {

	@Bean
	JavaMailSender javaMailSender(EmailProperties properties) {
		String host = properties.getSmtp().getHost();
		if (host == null || host.isBlank()) {
			throw new IllegalStateException(
					"app.email.smtp.host is required when app.email.provider is 'smtp'");
		}
		JavaMailSenderImpl sender = new JavaMailSenderImpl();
		sender.setHost(host);
		sender.setPort(properties.getSmtp().getPort());
		Properties props = sender.getJavaMailProperties();
		if (properties.getSmtp().getUsername() != null
				&& !properties.getSmtp().getUsername().isBlank()) {
			sender.setUsername(properties.getSmtp().getUsername());
			sender.setPassword(properties.getSmtp().getPassword());
			props.put("mail.smtp.auth", "true");
		}
		if (properties.getSmtp().isStarttlsEnabled()) {
			props.put("mail.smtp.starttls.enable", "true");
		}
		return sender;
	}
}
