package com.sourabh.smartqueue.auth;

/**
 * A public auth endpoint's attempt budget is exhausted. Renders as
 * {@code 429} with a stable code — clients back off and retry after
 * the window. Never carries the key, the count, or any account
 * information.
 */
public class RateLimitExceededException extends RuntimeException {

	public RateLimitExceededException(String message) {
		super(message);
	}
}
