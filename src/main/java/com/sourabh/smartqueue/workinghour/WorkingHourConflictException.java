package com.sourabh.smartqueue.workinghour;

/**
 * A working-hour interval overlaps an existing one for the same
 * branch and day. The database exclusion constraint arbitrates;
 * this exception translates its rejection into {@code 409} (never a
 * PostgreSQL detail for clients).
 */
public class WorkingHourConflictException extends RuntimeException {

	public WorkingHourConflictException(String message, Throwable cause) {
		super(message, cause);
	}
}
