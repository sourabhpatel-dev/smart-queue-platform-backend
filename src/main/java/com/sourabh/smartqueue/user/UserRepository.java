package com.sourabh.smartqueue.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for {@link User}.
 *
 * <p>The email finder serves the security phase (ADR-021): email is the
 * globally unique login identifier, looked up in its normalized form. The
 * organization search serves the Phase 3J user directory (ADR-025): one
 * query covers the unfiltered, status-filtered, role-filtered, and
 * combined cases — roles join the {@code user_role} collection, so
 * {@code DISTINCT} collapses the fan-out and the explicit count keeps
 * pagination exact.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

	/**
	 * @param email already-normalized via {@link User#normalizeEmail(String)}.
	 */
	Optional<User> findByEmail(String email);

	@Query(value = """
			SELECT DISTINCT u FROM User u LEFT JOIN u.roles r
			WHERE u.organization.id = :organizationId
			AND (:status IS NULL OR u.status = :status)
			AND (:role IS NULL OR r = :role)
			""",
			countQuery = """
					SELECT COUNT(DISTINCT u) FROM User u LEFT JOIN u.roles r
					WHERE u.organization.id = :organizationId
					AND (:status IS NULL OR u.status = :status)
					AND (:role IS NULL OR r = :role)
					""")
	Page<User> searchByOrganization(@Param("organizationId") UUID organizationId,
			@Param("status") UserStatus status,
			@Param("role") RoleType role,
			Pageable pageable);
}
