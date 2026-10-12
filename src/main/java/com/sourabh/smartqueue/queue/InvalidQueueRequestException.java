package com.sourabh.smartqueue.queue;

/**
 * A queue request failed validation (unknown queue/counter/customer,
 * inactive entities, cross-organization mismatch).
 */
public class InvalidQueueRequestException extends RuntimeException {

	public InvalidQueueRequestException(String message) {
		super(message);
	}
}
