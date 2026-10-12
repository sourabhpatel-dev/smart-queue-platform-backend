package com.sourabh.smartqueue.queue;

/**
 * A queue entry id referenced no existing entry.
 */
public class QueueEntryNotFoundException extends RuntimeException {

	public QueueEntryNotFoundException(String message) {
		super(message);
	}
}
