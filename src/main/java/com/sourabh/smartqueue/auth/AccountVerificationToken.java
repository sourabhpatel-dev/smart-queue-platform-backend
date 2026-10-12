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
 * Pending email verification for one user (see ADR-037).
 *
 * <p>One live row per user at most ({@code uq_verification_user}):
 * registration creates it, verification consumes it ({@code usedAt}),
 * resend replaces it. Only the SHA-256 digest is stored — the raw
 * token exists transiently at creation and travels exactly once in
 * the verification email. Liveness is a query predicate
 * ({@code usedAt IS NULL AND expiresAt > now}), never a status
 * column. Used rows are kept as the reuse proof for the scheduled
 * cleanup.
 */
@Entity
@Table(name = "account_verification_token")
public class AccountVerificationToken extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "user_id",
			nullable = false,
			updatable = false,
			foreignKey = @ForeignKey(name = "fk_verification_user"))
	private User user;

	@NotNull
	@Column(name = "token_hash", nullable = false, length = 64, updatable = false)
	private String tokenHash;

	@NotNull
	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	protected AccountVerificationToken() {
		// JPA only.
	}

	public AccountVerificationToken(User user, String tokenHash, Instant expiresAt) {
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
			throw new IllegalStateException("verification token already used");
		}
		this.usedAt = usedAt;
	}
}
