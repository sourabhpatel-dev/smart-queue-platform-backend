package com.sourabh.smartqueue.queue;

/**
 * A walk-in attempted to join a queue that is not ACTIVE.
 */
public class QueueNotActiveException extends RuntimeException {

	public QueueNotActiveException(String message) {
		super(message);
	}
}
