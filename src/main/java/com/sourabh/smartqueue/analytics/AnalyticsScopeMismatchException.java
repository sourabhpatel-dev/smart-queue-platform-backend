package com.sourabh.smartqueue.analytics;

/**
 * A same-organization analytics filter combination that cannot
 * describe a real scope — e.g. a service that does not belong to the
 * requested branch (see ADR-035).
 *
 * <p>Renders as {@code 422} like every other same-org mismatch in
 * this codebase (never {@code 404}: both ids exist and belong to the
 * caller, they just do not belong together; never an empty report: a
 * silent zero-row answer would mislead instead of correcting the
 * request).
 */
public class AnalyticsScopeMismatchException extends RuntimeException {

	public AnalyticsScopeMismatchException(String message) {
		super(message);
	}
}
