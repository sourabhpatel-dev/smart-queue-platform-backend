package com.sourabh.smartqueue.branch;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sourabh.smartqueue.branch.BranchStatus;

/**
 * Persistence access for {@link Branch}.
 *
 * <p>Scoped listing/lookup for the Phase 3H management API (see ADR-023):
 * every read pins the caller's organization id, so cross-tenant rows are
 * structurally unreachable. Single-id reads elsewhere keep using
 * {@code findById} plus the service's explicit ownership check (the
 * scope-safe-404 rule).
 */
public interface BranchRepository extends JpaRepository<Branch, UUID> {

	Page<Branch> findByOrganizationId(UUID organizationId, Pageable pageable);

	/** All branches of one organization (e.g. the applicant's initial branch). */
	java.util.List<Branch> findByOrganizationId(UUID organizationId);

	Page<Branch> findByOrganizationIdAndStatus(UUID organizationId,
			BranchStatus status, Pageable pageable);

	/**
	 * Active branches of one organization, name-ordered, for the
	 * public discovery directory (Phase 4I). The caller passes an
	 * explicitly chosen organization id — directory scope, not
	 * tenant scope.
	 */
	java.util.List<Branch> findByOrganizationIdAndStatusOrderByName(
			UUID organizationId, BranchStatus status);
}
