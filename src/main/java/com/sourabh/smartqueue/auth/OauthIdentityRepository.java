package com.sourabh.smartqueue.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link OauthIdentity}. Lookups run in both
 * directions: provider+subject on every social callback (which local
 * account, if any), user+provider when linking (exactly one link per
 * provider per user).
 */
public interface OauthIdentityRepository extends JpaRepository<OauthIdentity, UUID> {

	Optional<OauthIdentity> findByProviderAndSubject(String provider, String subject);

	Optional<OauthIdentity> findByUserIdAndProvider(UUID userId, String provider);
}
