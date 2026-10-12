package com.sourabh.smartqueue.queue;

/**
 * A database integrity rejection during a queue operation (duplicate active
 * entry, duplicate token, lost token-counter insert race) translated into a
 * domain outcome — never a leaked constraint name.
 */
public class QueueConflictException extends RuntimeException {

	public QueueConflictException(String message) {
		super(message);
	}

	public QueueConflictException(String message, Throwable cause) {
		super(message, cause);
	}
}
