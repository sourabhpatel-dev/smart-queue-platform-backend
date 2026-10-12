package com.sourabh.smartqueue.queue;

/**
 * An invalid {@code QueueEntry} state transition (e.g. completing a waiting
 * entry, or touching a terminal one).
 */
public class QueueEntryStateException extends RuntimeException {

	public QueueEntryStateException(String message) {
		super(message);
	}
}
