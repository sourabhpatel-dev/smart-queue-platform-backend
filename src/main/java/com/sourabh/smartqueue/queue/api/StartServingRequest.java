package com.sourabh.smartqueue.queue.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Names the counter an entry starts serving at (see ADR-027).
 *
 * <p>The counter must be active, at the entry's branch, and capable of
 * the entry's service — the lifecycle service enforces all three with
 * its established codes. Foreign-organization counters are scope-safe
 * {@code 404}s before any of that.
 */
public record StartServingRequest(
		@NotNull UUID counterId) {
}
