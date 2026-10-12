package com.sourabh.smartqueue.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sourabh.smartqueue.service.ServiceStatus;

/**
 * Persistence access for {@link Service}.
 *
 * <p>The scope-aware finder serves tenant-scoped application services
 * (ADR-019): a cross-branch id yields empty rather than a foreign entity.
 */
public interface ServiceRepository extends JpaRepository<Service, UUID> {

	/**
	 * Scope-aware load: returns the service only when it belongs to the
	 * given branch.
	 */
	Optional<Service> findByIdAndBranchId(UUID id, UUID branchId);

	Page<Service> findByBranchId(UUID branchId, Pageable pageable);

	Page<Service> findByBranchIdAndStatus(UUID branchId, ServiceStatus status,
			Pageable pageable);

	/**
	 * Active services of one branch, name-ordered, for the public
	 * discovery directory (Phase 4I).
	 */
	List<Service> findByBranchIdAndStatusOrderByName(UUID branchId, ServiceStatus status);
}
