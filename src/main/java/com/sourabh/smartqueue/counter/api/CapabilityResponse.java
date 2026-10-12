package com.sourabh.smartqueue.counter.api;

import java.time.Instant;
import java.util.UUID;

import com.sourabh.smartqueue.counter.CounterCapability;

/**
 * Public capability view: "Counter X serves Service Y" (see ADR-024).
 *
 * <p>Carries the served service's identity inline (id, name, status) so
 * consumers need no second call for the common display case — bounded to
 * those three fields, never a full service embedding. Explicit manual
 * mapping, no framework.
 */
public record CapabilityResponse(
		UUID id,
		UUID organizationId,
		UUID branchId,
		UUID counterId,
		UUID serviceId,
		String serviceName,
		String serviceStatus,
		Instant createdAt) {

	public static CapabilityResponse from(CounterCapability capability) {
		return new CapabilityResponse(
				capability.getId(),
				capability.getBranch().getOrganization().getId(),
				capability.getBranch().getId(),
				capability.getCounter().getId(),
				capability.getService().getId(),
				capability.getService().getName(),
				capability.getService().getStatus().name(),
				capability.getCreatedAt());
	}
}
