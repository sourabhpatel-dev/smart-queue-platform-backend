package com.sourabh.smartqueue.email;

/**
 * Email transport failure. Carries the recipient and subject for
 * operational logs — never the body. Callers treat this as
 * best-effort degradation (log and continue), never as a reason to
 * fail the request: the persisted lifecycle row is the source of
 * truth, and resend endpoints exist for every email type.
 */
public class EmailDeliveryException extends RuntimeException {

	public EmailDeliveryException(String message, Throwable cause) {
		super(message, cause);
	}
}
