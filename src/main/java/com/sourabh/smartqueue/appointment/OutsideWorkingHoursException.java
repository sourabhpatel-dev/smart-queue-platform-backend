package com.sourabh.smartqueue.appointment;

/**
 * The requested interval is outside branch working hours: unknown weekday
 * schedule, a split-interval gap, or outside every interval.
 */
public class OutsideWorkingHoursException extends RuntimeException {

	public OutsideWorkingHoursException(String message) {
		super(message);
	}
}
