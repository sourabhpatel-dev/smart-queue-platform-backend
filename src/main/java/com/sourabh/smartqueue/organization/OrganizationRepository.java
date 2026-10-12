package com.sourabh.smartqueue.organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

/**
 * Persistence access for {@link Organization}.
 *
 * <p>The active-ordered finder serves the public discovery directory
 * (Phase 4I): registration needs the list of joinable organizations.
 * The locked finder serves platform review decisions (ADR-041):
 * approve/reject serialize on the row lock so concurrent decisions
 * cannot double-provision memberships or fork the status.
 */
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

	List<Organization> findByStatusOrderByName(OrganizationStatus status);

	List<Organization> findByStatusOrderByCreatedAtAsc(OrganizationStatus status);

	/** The row with a pessimistic write lock — review decisions only. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Organization> findLockedById(UUID id);
}
