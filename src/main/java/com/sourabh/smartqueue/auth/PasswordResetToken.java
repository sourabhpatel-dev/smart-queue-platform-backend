package com.sourabh.smartqueue.auth;

import java.time.Instant;

import com.sourabh.smartqueue.common.BaseAuditableEntity;
import com.sourabh.smartqueue.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

/**
 * Pending password reset for one user (see ADR-037).
 *
 * <p>Same single-use shape as verification: one live row per user
 * ({@code uq_reset_user} — a new request replaces the old link, so at
 * most one reset link works at a time), digest-only storage, expiry
 * enforcement, kept-after-use as the reuse proof. Consuming the token
 * sets the new password through the single credential funnel (which
 * retires all prior sessions) and proves inbox access (which also
 * consumes any live verification token — see the reset service).
 */
@Entity
@Table(name = "password_reset_token")
public class PasswordResetToken extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "user_id",
			nullable = false,
			updatable = false,
			foreignKey = @ForeignKey(name = "fk_reset_user"))
	private User user;

	@NotNull
	@Column(name = "token_hash", nullable = false, length = 64, updatable = false)
	private String tokenHash;

	@NotNull
	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	protected PasswordResetToken() {
		// JPA only.
	}

	public PasswordResetToken(User user, String tokenHash, Instant expiresAt) {
		if (user == null) {
			throw new IllegalArgumentException("user is required");
		}
		if (tokenHash == null || tokenHash.isBlank()) {
			throw new IllegalArgumentException("token hash is required");
		}
		if (expiresAt == null) {
			throw new IllegalArgumentException("expiry is required");
		}
		this.user = user;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
	}

	public User getUser() {
		return user;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getUsedAt() {
		return usedAt;
	}

	public boolean isLive(Instant now) {
		return usedAt == null && expiresAt.isAfter(now);
	}

	/** One-way: a consumed token never reopens. */
	public void markUsed(Instant usedAt) {
		if (this.usedAt != null) {
			throw new IllegalStateException("reset token already used");
		}
		this.usedAt = usedAt;
	}
}
