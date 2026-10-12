package com.sourabh.smartqueue.auth.oauth;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.auth.AccountVerificationTokenRepository;
import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.JwtService;
import com.sourabh.smartqueue.auth.OauthIdentity;
import com.sourabh.smartqueue.auth.OauthIdentityRepository;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.organization.OrganizationStatus;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.user.UserStatus;

/**
 * Social login: authorization-code exchange plus OIDC verification,
 * then the documented account-linking policy (see the OAuth decision
 * notes).
 *
 * <p>Transport ({@link OAuthTokenExchanger}) and policy (this class)
 * stay separate: the exchanger is HTTP-only and unit-tested against
 * a stub server, while everything below is pure account logic
 * tested without any network.
 *
 * <p>Linking rules, enforced in this order:
 * <ol>
 *   <li>Known {@code (provider, subject)} → its user (must be
 *   {@code ACTIVE}, else the generic login failure — a disabled
 *   account stays disabled through every door).</li>
 *   <li>Unknown link, verified provider email matching an
 *   {@code ACTIVE} user → link and log in (the provider proved the
 *   inbox; any pending verification token is consumed as a side
 *   effect). Unverified provider emails and inactive accounts get
 *   the generic failure — never a second account, never a hint.</li>
 *   <li>Unknown link, new email → only with a verified provider
 *   email AND an explicit, active organization: creates a
 *   {@code CUSTOMER} there and links. Business onboarding stays
 *   password-based (documented limitation).</li>
 * </ol>
 *
 * <p>Every identity failure below shares the generic login failure
 * (no enumeration, no provider detail for callers); request-shape
 * problems (disabled provider, unknown provider, missing
 * organization) are {@code 400}. Codes, tokens, and secrets never
 * reach logs.
 */
@Service
public class OAuthService {

	private static final Logger log = LoggerFactory.getLogger(OAuthService.class);

	private final OAuthProperties properties;
	private final OAuthTokenExchanger exchanger;
	private final OidcTokenVerifier verifier;
	private final OauthIdentityRepository identities;
	private final UserRepository users;
	private final OrganizationRepository organizations;
	private final AccountVerificationTokenRepository verifications;
	private final JwtService jwtService;
	private final Clock clock;

	public OAuthService(OAuthProperties properties,
			OAuthTokenExchanger exchanger,
			OidcTokenVerifier verifier,
			OauthIdentityRepository identities,
			UserRepository users,
			OrganizationRepository organizations,
			AccountVerificationTokenRepository verifications,
			JwtService jwtService,
			Clock clock) {
		this.properties = properties;
		this.exchanger = exchanger;
		this.verifier = verifier;
		this.identities = identities;
		this.users = users;
		this.organizations = organizations;
		this.verifications = verifications;
		this.jwtService = jwtService;
		this.clock = clock;
	}

	/** Enabled providers for the login UI (names only — never secrets). */
	public List<String> enabledProviders() {
		ArrayList<String> enabled = new ArrayList<>();
		if (properties.getGoogle().isEnabled()) {
			enabled.add(OauthProvider.GOOGLE.name());
		}
		if (properties.getApple().isEnabled()) {
			enabled.add(OauthProvider.APPLE.name());
		}
		return List.copyOf(enabled);
	}

	@Transactional
	public AuthService.LoginResult authenticate(String providerName, String code,
			String redirectUri, UUID organizationId) {
		OauthProvider provider = providerOf(providerName);
		if (code == null || code.isBlank() || redirectUri == null || redirectUri.isBlank()) {
			throw new InvalidRequestException("INVALID_OAUTH",
					"Authorization code and redirect URI are required");
		}
		String idToken = exchanger.exchangeCode(provider, code, redirectUri);
		return loginWithIdentity(provider, verify(provider, idToken), organizationId);
	}

	/**
	 * Validated-identity login: the policy half of
	 * {@link #authenticate}, without any network. Public as an
	 * explicit test seam for the linking matrix (production calls
	 * flow through {@link #authenticate}).
	 */
	public AuthService.LoginResult loginWithIdentity(OauthProvider provider,
			VerifiedIdentity identity, UUID organizationId) {
		User user = resolveUser(provider, identity, organizationId);
		// Same identity rule as password login (ADR-040): customers are
		// platform-level identities (null organization); operational
		// principals use the membership resolution in AuthService — the
		// OAuth provisioning path keeps its current organization-scoped
		// account creation, but the legacy column never authorizes.
		boolean operational = user.getRoles().stream().anyMatch(
				role -> role == com.sourabh.smartqueue.user.RoleType.STAFF
						|| role == com.sourabh.smartqueue.user.RoleType.MANAGER
						|| role == com.sourabh.smartqueue.user.RoleType.ADMIN);
		UUID resolvedOrganizationId = !operational ? null
				: user.getOrganization() == null ? null : user.getOrganization().getId();
		AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
				user.getId(),
				resolvedOrganizationId,
				user.getEmail(),
				Set.copyOf(user.getRoles()));
		JwtService.IssuedToken issued = jwtService.issueToken(principal);
		log.info("oauth login: provider={}", provider);
		return new AuthService.LoginResult(issued.token(), principal, issued.expiresAt());
	}

	private OauthProvider providerOf(String providerName) {
		OauthProvider provider;
		try {
			provider = providerName == null ? null : OauthProvider.valueOf(providerName);
		} catch (IllegalArgumentException e) {
			throw new InvalidRequestException("INVALID_PROVIDER",
					"Unknown identity provider");
		}
		if (provider == null) {
			throw new InvalidRequestException("INVALID_PROVIDER", "Unknown identity provider");
		}
		boolean enabled = provider == OauthProvider.GOOGLE
				? properties.getGoogle().isEnabled()
				: properties.getApple().isEnabled();
		if (!enabled) {
			throw new InvalidRequestException("INVALID_PROVIDER",
					"That sign-in method is not enabled");
		}
		return provider;
	}

	private VerifiedIdentity verify(OauthProvider provider, String idToken) {
		try {
			if (provider == OauthProvider.GOOGLE) {
				return verifier.verify(idToken, OidcTokenVerifier.GOOGLE_ISSUER,
						properties.getGoogle().getClientId(), OidcTokenVerifier.GOOGLE_JWKS);
			}
			return verifier.verify(idToken, OidcTokenVerifier.APPLE_ISSUER,
					properties.getApple().getClientId(), OidcTokenVerifier.APPLE_JWKS);
		} catch (RuntimeException e) {
			log.warn("oauth identity verification failed: provider={}", provider);
			throw new BadCredentialsException(AuthService.GENERIC_FAILURE);
		}
	}

	private User resolveUser(OauthProvider provider, VerifiedIdentity identity,
			UUID organizationId) {
		OauthIdentity link = identities
				.findByProviderAndSubject(provider.name(), identity.subject())
				.orElse(null);
		if (link != null) {
			User user = link.getUser();
			if (user.getStatus() != UserStatus.ACTIVE) {
				throw new BadCredentialsException(AuthService.GENERIC_FAILURE);
			}
			return user;
		}
		String normalized = User.normalizeEmail(identity.email());
		User existing = users.findByEmail(normalized).orElse(null);
		if (existing != null) {
			if (existing.getStatus() != UserStatus.ACTIVE || !identity.emailVerified()) {
				throw new BadCredentialsException(AuthService.GENERIC_FAILURE);
			}
			consumeVerification(existing.getId());
			return link(existing, provider, identity.subject());
		}
		if (!identity.emailVerified()) {
			throw new BadCredentialsException(AuthService.GENERIC_FAILURE);
		}
		Organization organization = organizationId == null ? null
				: organizations.findById(organizationId).orElse(null);
		if (organization == null || organization.getStatus() != OrganizationStatus.ACTIVE) {
			throw new ResourceNotFoundException("ORGANIZATION_NOT_FOUND",
					"Organization not found");
		}
		User user = new User(organization, normalized, displayFrom(identity.email()));
		user.grantRole(RoleType.CUSTOMER);
		users.save(user);
		log.info("oauth provisioned customer: organization={}", organization.getId());
		return link(user, provider, identity.subject());
	}

	private User link(User user, OauthProvider provider, String subject) {
		try {
			OauthIdentity created =
					identities.save(new OauthIdentity(user, provider.name(), subject));
			return created.getUser();
		} catch (DataIntegrityViolationException e) {
			// Lost a link race: the other callback won and its row is
			// the link. Re-read rather than duplicate.
			return identities.findByProviderAndSubject(provider.name(), subject)
					.map(OauthIdentity::getUser)
					.orElse(user);
		}
	}

	private void consumeVerification(UUID userId) {
		verifications.findByUserId(userId).ifPresent(pending -> {
			if (pending.getUsedAt() == null) {
				pending.markUsed(clock.instant());
			}
		});
	}

	private String displayFrom(String email) {
		String prefix = email.split("@")[0];
		return prefix.isBlank() ? email : prefix;
	}
}
