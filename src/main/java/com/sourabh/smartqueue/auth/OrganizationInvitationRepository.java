package com.sourabh.smartqueue.auth;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for {@link OrganizationInvitation} (see
 * ADR-037). Public acceptance resolves by digest; management lists
 * by organization (never cross-tenant); resend finds live rows for
 * the same (organization, email) to revoke before re-issuing.
 */
public interface OrganizationInvitationRepository
		extends JpaRepository<OrganizationInvitation, UUID> {

	Optional<OrganizationInvitation> findByTokenHash(String tokenHash);

	/**
	 * Live rows for one recipient in one organization (normally zero
	 * or one — history repeats, liveness does not).
	 */
	@Query("""
			SELECT i FROM OrganizationInvitation i
			WHERE i.organization.id = :organizationId
			AND i.email = :email
			AND i.acceptedAt IS NULL
			AND i.revokedAt IS NULL
			AND i.expiresAt > :now
			""")
	List<OrganizationInvitation> findLiveByOrganizationAndEmail(
			@Param("organizationId") UUID organizationId,
			@Param("email") String email,
			@Param("now") Instant now);

	Page<OrganizationInvitation> findByOrganizationId(UUID organizationId, Pageable pageable);

	/**
	 * Settled-or-expired rows for the bounded cleanup sweep, oldest
	 * first. IDs only — rows are deleted by id list, never hydrated.
	 * Live rows are never cleanup-eligible, however old.
	 */
	@Query("""
			SELECT i.id FROM OrganizationInvitation i
			WHERE i.acceptedAt IS NOT NULL
			OR i.revokedAt IS NOT NULL
			OR i.expiresAt < :now
			ORDER BY i.createdAt ASC
			""")
	List<UUID> findIdsEligibleForCleanup(@Param("now") Instant now, Pageable pageable);

	@Modifying
	@Query("DELETE FROM OrganizationInvitation i WHERE i.id IN :ids")
	int deleteByIds(@Param("ids") Collection<UUID> ids);
}
