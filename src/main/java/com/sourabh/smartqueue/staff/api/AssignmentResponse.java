package com.sourabh.smartqueue.staff.api;

import java.time.Instant;
import java.util.UUID;

import com.sourabh.smartqueue.staff.StaffAssignment;

/**
 * Public assignment view: "User X staffs Branch Y" (see ADR-025).
 *
 * <p>Identity linkage only — ids, both statuses, timestamps. The user and
 * branch details behind the ids live at their own endpoints. History is
 * preserved: deactivation flips the status, never deletes the row.
 * Explicit manual mapping, no framework.
 */
public record AssignmentResponse(
		UUID id,
		UUID organizationId,
		UUID userId,
		UUID branchId,
		String status,
		Instant createdAt,
		Instant updatedAt) {

	public static AssignmentResponse from(StaffAssignment assignment) {
		return new AssignmentResponse(
				assignment.getId(),
				assignment.getOrganization().getId(),
				assignment.getUser().getId(),
				assignment.getBranch().getId(),
				assignment.getStatus().name(),
				assignment.getCreatedAt(),
				assignment.getUpdatedAt());
	}
}
