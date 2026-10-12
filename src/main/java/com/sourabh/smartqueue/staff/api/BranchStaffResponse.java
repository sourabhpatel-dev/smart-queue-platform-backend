package com.sourabh.smartqueue.staff.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.sourabh.smartqueue.staff.StaffAssignment;

/**
 * One roster row for branch staff listings (see ADR-025).
 *
 * <p>A dedicated DTO (not a reused user view): the roster needs user
 * identity plus both lifecycle states in one row, without credentials
 * and without a second call per member. Assignment id first — it
 * addresses the lifecycle commands.
 */
public record BranchStaffResponse(
		UUID assignmentId,
		UUID userId,
		String email,
		String displayName,
		List<String> roles,
		String userStatus,
		String assignmentStatus,
		Instant assignedAt) {

	public static BranchStaffResponse from(StaffAssignment assignment) {
		return new BranchStaffResponse(
				assignment.getId(),
				assignment.getUser().getId(),
				assignment.getUser().getEmail(),
				assignment.getUser().getDisplayName(),
				assignment.getUser().getRoles().stream().map(Enum::name).sorted().toList(),
				assignment.getUser().getStatus().name(),
				assignment.getStatus().name(),
				assignment.getCreatedAt());
	}
}
