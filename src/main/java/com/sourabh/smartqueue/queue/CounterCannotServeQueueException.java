package com.sourabh.smartqueue.queue;

/**
 * A counter was asked to serve a queue it is not capable of serving: it
 * supports no services at all, or none matching the waiting entries.
 */
public class CounterCannotServeQueueException extends RuntimeException {

	public CounterCannotServeQueueException(String message) {
		super(message);
	}
}
