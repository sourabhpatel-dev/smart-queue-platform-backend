package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sourabh.smartqueue.auth.AccountEmailComposer;
import com.sourabh.smartqueue.auth.AccountLifecycleProperties;
import com.sourabh.smartqueue.auth.AccountTokenGenerator;
import com.sourabh.smartqueue.email.EmailMessage;
import com.sourabh.smartqueue.email.EmailProperties;
import com.sourabh.smartqueue.email.SmtpEmailSender;

/**
 * Real SMTP delivery proof for the local Mailpit relay (Phase 4R.3.2).
 *
 * <p>This is the only test allowed to claim delivery works: it sends
 * through {@link SmtpEmailSender} over a real TCP SMTP conversation to
 * the same {@code axllent/mailpit} image local developers run from
 * {@code compose.yaml}, then reads the captured message back through
 * Mailpit's HTTP API. Mocked-sender tests prove composition and
 * persistence only — never delivery.
 *
 * <p>No application database is involved: the sender, composer, and
 * token generator are wired manually in exactly the local-development
 * shape (plain SMTP, no auth, no STARTTLS — see
 * {@code docs/development/README.md}, "Account email").
 */
@Testcontainers
class SmtpMailpitDeliveryTests {

	@Container
	static GenericContainer<?> mailpit = new GenericContainer<>(
			DockerImageName.parse("axllent/mailpit:v1.27"))
			.withExposedPorts(1025, 8025);

	private final HttpClient http = HttpClient.newHttpClient();
	private final ObjectMapper mapper = new ObjectMapper();

	private String apiBase() {
		return "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025);
	}

	@BeforeEach
	void resetInbox() throws Exception {
		HttpRequest delete = HttpRequest.newBuilder()
				.uri(URI.create(apiBase() + "/api/v1/messages"))
				.DELETE()
				.timeout(Duration.ofSeconds(10))
				.build();
		http.send(delete, HttpResponse.BodyHandlers.discarding());
	}

	@Test
	void verificationEmailArrivesInMailpitWithActionableLink() throws Exception {
		String recipient = "capture-" + UUID.randomUUID() + "@example.com";
		// A genuine generator-issued token: proves URL-safe tokens
		// survive SMTP transport verbatim (no escaping ceremony).
		String rawToken = new AccountTokenGenerator().generate();

		EmailProperties properties = new EmailProperties();
		properties.setFromAddress("no-reply@smartqueue.local");
		properties.setFrontendBaseUrl("http://localhost:5173");
		properties.getSmtp().setHost(mailpit.getHost());
		properties.getSmtp().setPort(mailpit.getMappedPort(1025));

		AccountEmailComposer composer = new AccountEmailComposer(
				properties, new AccountLifecycleProperties());
		EmailMessage message = composer.verificationEmail(recipient, "Mailpit", rawToken);

		SmtpEmailSender sender = new SmtpEmailSender(mailSender(properties), properties);
		sender.send(message);

		String id = waitForMessageWithSubject("Verify your SmartQueue account");
		String full = get("/api/v1/message/" + id);

		// The captured message carries the actionable verification link
		// for the right recipient — matched on raw JSON so the proof does
		// not depend on Mailpit's field naming.
		assertThat(full).contains("http://localhost:5173/verify-email?token=" + rawToken);
		assertThat(full).contains(recipient);
		assertThat(full).contains("Verify your SmartQueue account");

		// No duplicate delivery from a single send.
		Thread.sleep(2000);
		assertThat(totalMessages()).isEqualTo(1);
	}

	private JavaMailSenderImpl mailSender(EmailProperties properties) {
		// Local-development relay shape (mirrors EmailConfiguration with
		// no username and no STARTTLS): plain SMTP to Mailpit.
		JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
		mailSender.setHost(properties.getSmtp().getHost());
		mailSender.setPort(properties.getSmtp().getPort());
		Properties timeouts = mailSender.getJavaMailProperties();
		timeouts.put("mail.smtp.connectiontimeout", "10000");
		timeouts.put("mail.smtp.timeout", "10000");
		return mailSender;
	}

	private String waitForMessageWithSubject(String subject) throws Exception {
		long deadline = System.currentTimeMillis() + 30_000;
		while (true) {
			String list = get("/api/v1/messages?limit=50");
			JsonNode root = mapper.readTree(list);
			for (JsonNode item : root.path("messages")) {
				if (subject.equals(item.path("Subject").asText())) {
					return item.path("ID").asText();
				}
			}
			if (root.path("total").asInt(0) > 0 && subject.isEmpty()) {
				return root.path("messages").path(0).path("ID").asText();
			}
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("timed out waiting for email: " + subject
						+ "; last list response: " + list);
			}
			Thread.sleep(500);
		}
	}

	private int totalMessages() throws Exception {
		return mapper.readTree(get("/api/v1/messages?limit=50")).path("total").asInt(0);
	}

	private String get(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(apiBase() + path))
				.GET()
				.timeout(Duration.ofSeconds(10))
				.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
	}
}
