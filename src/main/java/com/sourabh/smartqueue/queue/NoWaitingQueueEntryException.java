package com.sourabh.smartqueue.queue;

/**
 * A {@code callNext} found no WAITING entry for the services a counter
 * serves. An explicit outcome — never a silent null.
 */
public class NoWaitingQueueEntryException extends RuntimeException {

	public NoWaitingQueueEntryException(String message) {
		super(message);
	}
}
