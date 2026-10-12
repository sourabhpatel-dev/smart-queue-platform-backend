package com.sourabh.smartqueue.notification;

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
 * Notification persistence boundary (see ADR-032).
 *
 * <p>Every read pins the recipient: there is no cross-user or
 * cross-organization listing, and callers additionally verify the
 * organization before returning a row (scope-safe 404 otherwise).
 * The unread count runs in the database — the frontend never pages
 * through rows to compute a badge.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

	Optional<Notification> findByIdAndRecipientUserId(UUID id, UUID recipientUserId);

	Page<Notification> findByRecipientUserId(UUID recipientUserId, Pageable pageable);

	Page<Notification> findByRecipientUserIdAndReadAtIsNull(UUID recipientUserId, Pageable pageable);

	Page<Notification> findByRecipientUserIdAndReadAtIsNotNull(UUID recipientUserId, Pageable pageable);

	long countByRecipientUserIdAndReadAtIsNull(UUID recipientUserId);

	boolean existsByEventId(UUID eventId);

	@Modifying
	@Query("""
			UPDATE Notification n SET n.readAt = :readAt
			WHERE n.recipientUser.id = :recipientUserId AND n.readAt IS NULL
			""")
	int markAllRead(@Param("recipientUserId") UUID recipientUserId,
			@Param("readAt") java.time.Instant readAt);

	/**
	 * Retention id pages (see ADR-033): the scheduled cleanup deletes by
	 * id batches, never by hydrating entities — no N+1, no persistence
	 * context full of rows that are about to disappear. The timestamp
	 * predicate needs no tenant data: cleanup is a global maintenance
	 * operation over creation time, and deleting a row touches no other
	 * tenant's data. Ordered oldest-first so each batch removes the
	 * most overdue rows first.
	 */
	@Query("""
			SELECT n.id FROM Notification n
			WHERE n.readAt IS NOT NULL AND n.createdAt < :cutoff
			ORDER BY n.createdAt ASC
			""")
	List<UUID> findReadIdsEligibleForRetention(@Param("cutoff") java.time.Instant cutoff,
			Pageable pageable);

	@Query("""
			SELECT n.id FROM Notification n
			WHERE n.readAt IS NULL AND n.createdAt < :cutoff
			ORDER BY n.createdAt ASC
			""")
	List<UUID> findUnreadIdsEligibleForRetention(@Param("cutoff") java.time.Instant cutoff,
			Pageable pageable);

	@Modifying
	@Query("DELETE FROM Notification n WHERE n.id IN :ids")
	int deleteByIds(@Param("ids") Collection<UUID> ids);
}
