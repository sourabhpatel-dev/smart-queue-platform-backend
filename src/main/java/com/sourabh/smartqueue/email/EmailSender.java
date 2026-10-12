package com.sourabh.smartqueue.email;

/**
 * Outbound email port (see AGENTS.md §11 and the email decision
 * notes). Business logic depends on this abstraction — never on a
 * provider SDK, never on SMTP directly. Delivery is always
 * best-effort-after-commit: callers send from an
 * {@code afterCommit} synchronization (the established
 * notification pattern), so transport failures degrade to logs and
 * can never roll back committed account state.
 */
public interface EmailSender {

	/**
	 * Attempts delivery. Implementations must never log message
	 * bodies (they carry single-use tokens) and must never throw
	 * provider internals to callers — wrap in
	 * {@link EmailDeliveryException}.
	 */
	void send(EmailMessage message);
}
