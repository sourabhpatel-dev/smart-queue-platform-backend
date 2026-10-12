package com.sourabh.smartqueue.appointment;

/**
 * The requested date is a branch holiday (full closure).
 */
public class BranchClosedException extends RuntimeException {

	public BranchClosedException(String message) {
		super(message);
	}
}
