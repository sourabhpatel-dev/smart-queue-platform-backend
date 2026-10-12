package com.sourabh.smartqueue.membership;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link OrganizationMembership} (see ADR-040).
 *
 * <p>Minimal by design (Phase 4R.2 is the persistence foundation): the
 * query methods exist to serve the later authentication/authorization
 * phases — resolve one user's role at one organization, load a user's
 * active memberships, load an organization's active members, and answer
 * existence. No management API, no invitation surface, no admin UI in
 * this phase.
 */
public interface OrganizationMembershipRepository
		extends JpaRepository<OrganizationMembership, UUID> {

	/** The single grant of a user at one organization, if any. */
	Optional<OrganizationMembership> findByUserIdAndOrganizationId(
			UUID userId, UUID organizationId);

	/** A user's memberships in the given state (e.g. ACTIVE grants only). */
	List<OrganizationMembership> findByUserIdAndStatus(
			UUID userId, MembershipStatus status);

	/** An organization's members in the given state (e.g. ACTIVE staff). */
	List<OrganizationMembership> findByOrganizationIdAndStatus(
			UUID organizationId, MembershipStatus status);

	/** Existence check for the (user, organization) pair. */
	boolean existsByUserIdAndOrganizationId(UUID userId, UUID organizationId);

	/**
	 * Existence check for a grant in a given state — the authorization
	 * boundary asks "does this ACTIVE membership exist?" (ADR-040).
	 */
	boolean existsByUserIdAndOrganizationIdAndStatus(UUID userId, UUID organizationId,
			MembershipStatus status);
}
