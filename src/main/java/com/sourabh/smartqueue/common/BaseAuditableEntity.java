package com.sourabh.smartqueue.common;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/**
 * Shared persistence foundation for domain entities (see ADR-006).
 *
 * <p>Carries the two conventions every entity must follow: application-assigned
 * UUID identifiers and lifecycle-managed UTC timestamps. A single superclass
 * (rather than duplicated callbacks) guarantees identical timestamp behavior
 * across all future entities.
 *
 * <p>Equality is intentionally identity-based (no equals/hashCode override):
 * entities are never held in hash-based collections in this codebase, and
 * id-based equality interacts badly with lazy proxies.
 */
@MappedSuperclass
public abstract class BaseAuditableEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id = UUID.randomUUID();

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected BaseAuditableEntity() {
		// JPA requires a no-arg constructor; id is assigned at construction so it
		// is available before persist (DTO mapping, tests) with no extra round-trip.
	}

	public UUID getId() {
		return id;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}
}
