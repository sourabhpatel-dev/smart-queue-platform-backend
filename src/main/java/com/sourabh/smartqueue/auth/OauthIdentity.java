package com.sourabh.smartqueue.auth;

import com.sourabh.smartqueue.common.BaseAuditableEntity;
import com.sourabh.smartqueue.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A verified external identity linked to one local user (see the
 * OAuth decision notes).
 *
 * <p>Created only after the provider's ID token validates (issuer,
 * audience, signature, expiry) and the account-linking policy
 * resolves (verified-email match to an existing user, or a new
 * customer in an explicitly chosen organization). The link is
 * {@code (provider, subject)} — the provider's opaque stable id,
 * never parsed, never displayed. Email is deliberately NOT stored:
 * matching happens once at link time from the verified token, so
 * later provider-side email changes cannot drift or hijack the row.
 * Uniqueness both ways: one local account per external identity,
 * one linked identity per provider per user.
 */
@Entity
@Table(name = "oauth_identity")
public class OauthIdentity extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "user_id",
			nullable = false,
			updatable = false,
			foreignKey = @ForeignKey(name = "fk_oauth_user"))
	private User user;

	@NotBlank
	@Size(max = 20)
	@Column(name = "provider", nullable = false, length = 20, updatable = false)
	private String provider;

	@NotBlank
	@Size(max = 255)
	@Column(name = "subject", nullable = false, length = 255, updatable = false)
	private String subject;

	protected OauthIdentity() {
		// JPA only.
	}

	public OauthIdentity(User user, String provider, String subject) {
		if (user == null) {
			throw new IllegalArgumentException("user is required");
		}
		if (provider == null || provider.isBlank()) {
			throw new IllegalArgumentException("provider is required");
		}
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("subject is required");
		}
		this.user = user;
		this.provider = provider;
		this.subject = subject;
	}

	public User getUser() {
		return user;
	}

	public String getProvider() {
		return provider;
	}

	public String getSubject() {
		return subject;
	}
}
