package com.sourabh.smartqueue.staff;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link StaffAssignment}.
 *
 * <p>The existence checks serve branch authorization (ADR-021): a staff
 * member may operate at a branch only with an {@code ACTIVE} assignment
 * there. Organization membership alone grants no branch access. The paged
 * finders serve the Phase 3J roster APIs (ADR-025): per-user and
 * per-branch listings, each optionally narrowed by assignment status.
 */
public interface StaffAssignmentRepository extends JpaRepository<StaffAssignment, UUID> {

	boolean existsByUserIdAndBranchIdAndStatus(
			UUID userId, UUID branchId, StaffAssignmentStatus status);

	Optional<StaffAssignment> findByUserIdAndBranchId(UUID userId, UUID branchId);

	Page<StaffAssignment> findByUserId(UUID userId, Pageable pageable);

	Page<StaffAssignment> findByUserIdAndStatus(UUID userId,
			StaffAssignmentStatus status, Pageable pageable);

	Page<StaffAssignment> findByBranchId(UUID branchId, Pageable pageable);

	Page<StaffAssignment> findByBranchIdAndStatus(UUID branchId,
			StaffAssignmentStatus status, Pageable pageable);
}
