package com.sourabh.smartqueue.counter.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Capability creation payload (see ADR-024).
 *
 * <p>One field: which service the path counter should serve. The counter
 * comes from the path, the branch from both rows' ownership — the service
 * must live at the counter's own branch (cross-branch links are rejected
 * with {@code 422}, cross-organization rows are scope-safe {@code 404}s).
 */
public record AddCapabilityRequest(
		@NotNull UUID serviceId) {
}
