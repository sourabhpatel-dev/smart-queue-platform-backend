package com.sourabh.smartqueue.appointment.api;

import java.time.Instant;

/**
 * One bookable appointment start: absolute instants (the branch-local
 * date and zone travel on the enclosing response). Chronological —
 * the reader emits generation order, never re-sorted downstream.
 */
public record AvailabilitySlot(
		Instant start,
		Instant end) {
}
