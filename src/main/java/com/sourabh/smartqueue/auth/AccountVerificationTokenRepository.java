package com.sourabh.smartqueue.auth;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for {@link AccountVerificationToken} (see
 * ADR-037). Lookups are by digest (the emailed value hashed) or by
 * user (the login gate and resend replacement) — raw tokens never
 * reach this layer.
 */
public interface AccountVerificationTokenRepository
		extends JpaRepository<AccountVerificationToken, UUID> {

	Optional<AccountVerificationToken> findByTokenHash(String tokenHash);

	Optional<AccountVerificationToken> findByUserId(UUID userId);

	/**
	 * Expired-or-used rows for the bounded cleanup sweep, oldest
	 * first. IDs only — rows are deleted by id list, never hydrated.
	 */
	@Query("""
			SELECT t.id FROM AccountVerificationToken t
			WHERE t.usedAt IS NOT NULL OR t.expiresAt < :now
			ORDER BY t.createdAt ASC
			""")
	List<UUID> findIdsEligibleForCleanup(@Param("now") Instant now, Pageable pageable);

	@Modifying
	@Query("DELETE FROM AccountVerificationToken t WHERE t.id IN :ids")
	int deleteByIds(@Param("ids") Collection<UUID> ids);
}
