package com.sourabh.smartqueue.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link UserCredential}.
 *
 * <p>Two focused lookups: credential by user (login + password changes) —
 * nothing queries "all credentials", so no other finder is created.
 */
public interface UserCredentialRepository extends JpaRepository<UserCredential, UUID> {

	Optional<UserCredential> findByUserId(UUID userId);
}
