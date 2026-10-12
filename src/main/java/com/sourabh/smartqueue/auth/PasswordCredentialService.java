package com.sourabh.smartqueue.auth;

import java.time.Clock;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Password provisioning and verification (see ADR-021).
 *
 * <p>BCrypt via Spring Security's {@link PasswordEncoder} — never custom
 * hashing, never plaintext, never manual string comparison. This service
 * owns the only two operations on the secret: storing a fresh hash
 * (provisioning, used by tests and future admin flows) and checking a
 * candidate during login. Login-failure semantics (generic errors, inactive
 * users, dummy-hash timing) live in {@link AuthService}, not here.
 */
@Service
public class PasswordCredentialService {

	/**
	 * Pre-computed BCrypt hash of a random value, used only to equalize
	 * login timing when no real hash exists to compare against (see
	 * {@link AuthService}). Never a real credential, never returned.
	 */
	static final String DUMMY_HASH =
			"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

	private final UserCredentialRepository credentials;
	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	public PasswordCredentialService(UserCredentialRepository credentials,
			UserRepository users,
			PasswordEncoder passwordEncoder,
			Clock clock) {
		this.credentials = credentials;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	/**
	 * Sets (or replaces) the user's password: hashes with BCrypt,
	 * upserts the single credential row, and stamps the change. The
	 * stamp is what retires previously issued tokens (see ADR-037) —
	 * every password-setting flow (registration, invitation
	 * acceptance, reset, change) funnels through here, so none can
	 * forget revocation.
	 *
	 * @throws IllegalArgumentException for unknown users or blank passwords.
	 */
	@Transactional
	public void setPassword(UUID userId, String rawPassword) {
		if (rawPassword == null || rawPassword.isBlank()) {
			throw new IllegalArgumentException("password must not be blank");
		}
		User user = users.findById(userId)
				.orElseThrow(() -> new IllegalArgumentException("unknown user: " + userId));
		String hash = passwordEncoder.encode(rawPassword);
		credentials.findByUserId(userId)
				.ifPresentOrElse(
						existing -> {
							existing.setPasswordHash(hash);
							existing.setPasswordChangedAt(clock.instant());
						},
						() -> {
							UserCredential created = new UserCredential(user, hash);
							created.setPasswordChangedAt(clock.instant());
							credentials.save(created);
						});
	}

	/**
	 * @return true when the candidate matches the stored hash, false when no
	 * credential exists or the candidate differs. Never throws for a missing
	 * row — callers translate absence into their own generic failure.
	 */
	@Transactional(readOnly = true)
	public boolean matches(UUID userId, String rawPassword) {
		return credentials.findByUserId(userId)
				.map(credential -> passwordEncoder.matches(rawPassword, credential.getPasswordHash()))
				.orElse(false);
	}
}
