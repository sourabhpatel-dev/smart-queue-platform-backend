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
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Password credential for one user (see ADR-021).
 *
 * <p>Deliberately separate from {@link User}: identity (email, roles,
 * status) and authentication secret have different lifecycles, and the
 * domain model stays free of security fields. Only a BCrypt hash is ever
 * stored — never a plaintext password, never a token, never login history.
 * At most one row per user ({@code uq_credential_user}).
 *
 * <p>{@code UserStatus} remains the source of truth for whether the account
 * may authenticate; this row carries no status of its own. A user without a
 * credential row simply cannot log in (same generic failure as a bad
 * password — no existence signal).
 */
@Entity
@Table(
		name = "user_credential",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_credential_user",
				columnNames = {"user_id"}))
public class UserCredential extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "user_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_credential_user"))
	private User user;

	@NotBlank
	@Size(max = 255)
	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

	/**
	 * When the password was last (re)set, from the project Clock. NULL
	 * means never — true for every row predating this column. The
	 * session-revocation check rejects tokens issued before this
	 * instant (see ADR-037); OAuth-only users have no row at all and
	 * are unaffected.
	 */
	@Column(name = "password_changed_at")
	private Instant passwordChangedAt;

	protected UserCredential() {
		// JPA only.
	}

	public UserCredential(User user, String passwordHash) {
		this.user = user;
		this.passwordHash = passwordHash;
	}

	public User getUser() {
		return user;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public Instant getPasswordChangedAt() {
		return passwordChangedAt;
	}

	public void setPasswordChangedAt(Instant passwordChangedAt) {
		this.passwordChangedAt = passwordChangedAt;
	}
}
