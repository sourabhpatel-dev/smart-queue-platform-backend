package com.sourabh.smartqueue.holiday;

/**
 * A holiday already exists for the branch on the given date. The
 * {@code uq_holiday_branch_date} constraint arbitrates; this
 * exception translates its rejection into {@code 409} (never a
 * PostgreSQL detail for clients).
 */
public class HolidayConflictException extends RuntimeException {

	public HolidayConflictException(String message, Throwable cause) {
		super(message, cause);
	}
}
