package com.sourabh.smartqueue.counter.api;

import java.time.Instant;
import java.util.UUID;

import com.sourabh.smartqueue.counter.ServiceCounter;

/**
 * Public counter view (see ADR-024).
 *
 * <p>API-level data only: UUIDs, status as a name string, timestamps as
 * ISO-8601. Served capabilities are NOT embedded — they live behind the
 * dedicated capability endpoints, keeping this view bounded. Explicit
 * manual mapping, no framework.
 */
public record CounterResponse(
		UUID id,
		UUID organizationId,
		UUID branchId,
		String name,
		String status,
		Instant createdAt,
		Instant updatedAt) {

	public static CounterResponse from(ServiceCounter counter) {
		return new CounterResponse(
				counter.getId(),
				counter.getBranch().getOrganization().getId(),
				counter.getBranch().getId(),
				counter.getName(),
				counter.getStatus().name(),
				counter.getCreatedAt(),
				counter.getUpdatedAt());
	}
}
