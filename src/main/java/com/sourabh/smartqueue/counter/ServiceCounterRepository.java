package com.sourabh.smartqueue.counter;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sourabh.smartqueue.counter.CounterStatus;

/**
 * Persistence access for {@link ServiceCounter}.
 *
 * <p>The scope-aware finder serves tenant-scoped application services
 * (ADR-019): a cross-branch id yields empty rather than a foreign entity.
 */
public interface ServiceCounterRepository extends JpaRepository<ServiceCounter, UUID> {

	/**
	 * Scope-aware load: returns the counter only when it belongs to the
	 * given branch.
	 */
	Optional<ServiceCounter> findByIdAndBranchId(UUID id, UUID branchId);

	Page<ServiceCounter> findByBranchId(UUID branchId, Pageable pageable);

	Page<ServiceCounter> findByBranchIdAndStatus(UUID branchId, CounterStatus status,
			Pageable pageable);
}
