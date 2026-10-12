package com.sourabh.smartqueue.user.application;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.PasswordCredentialService;
import com.sourabh.smartqueue.auth.TenantAccessPolicy;
import com.sourabh.smartqueue.common.api.ApiPage;
import com.sourabh.smartqueue.common.api.ApiPageRequest;
import com.sourabh.smartqueue.common.api.InvalidRequestException;
import com.sourabh.smartqueue.common.api.ResourceNotFoundException;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.MembershipStatus;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.user.InvalidUserRequestException;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserEmailConflictException;
import com.sourabh.smartqueue.user.UserRepository;
import com.sourabh.smartqueue.user.UserStatus;
import com.sourabh.smartqueue.user.api.CreateUserRequest;
import com.sourabh.smartqueue.user.api.UpdateUserRequest;
import com.sourabh.smartqueue.user.api.UserResponse;

/**
 * User management behind the tenant boundary (see ADR-025).
 *
 * <p>The user directory is management data: listing, creation, updates,
 * and lifecycle commands need {@code MANAGER}/{@code ADMIN} in the
 * caller's own organization. Single-record reads additionally allow the
 * subject themself (self-or-manager). Email is identity — set once at
 * creation (normalized by the entity, globally unique by constraint),
 * immutable afterwards. Roles replace wholesale on PUT; every granted
 * role stays organization-scoped, so no elevation can escape the tenant
 * (there is no platform admin to grant). Status moves only through the
 * explicit commands; deactivation preserves every historical reference
 * and simply fails login generically per the Phase 3F rule.
 */
@Service
public class UserManagementService {

	static final String CODE = "USER_NOT_FOUND";

	/** API sort names to persistence properties — the allow-list, nothing else. */
	static final Map<String, String> SORTABLE = Map.of(
			"email", "email",
			"displayName", "displayName",
			"status", "status",
			"createdAt", "createdAt",
			"updatedAt", "updatedAt");

	private final UserRepository users;
	private final OrganizationRepository organizations;
	private final PasswordCredentialService credentials;
	private final TenantAccessPolicy access;
	private final OrganizationMembershipRepository memberships;

	public UserManagementService(UserRepository users,
			OrganizationRepository organizations,
			PasswordCredentialService credentials,
			TenantAccessPolicy access,
			OrganizationMembershipRepository memberships) {
		this.users = users;
		this.organizations = organizations;
		this.credentials = credentials;
		this.access = access;
		this.memberships = memberships;
	}

	@Transactional(readOnly = true)
	public ApiPage<UserResponse> listUsers(AuthenticatedPrincipal principal,
			int page, int size, List<String> sorts, String statusFilter, String roleFilter) {
		access.requireOrganizationManagement(principal);
		Pageable pageable = ApiPageRequest.of(page, size, sorts, SORTABLE, "email");
		Page<User> result = users.searchByOrganization(principal.organizationId(),
				parseStatus(statusFilter), parseRole(roleFilter), pageable);
		return ApiPage.from(result.map(UserResponse::from));
	}

	@Transactional(readOnly = true)
	public UserResponse getUser(AuthenticatedPrincipal principal, UUID userId) {
		return UserResponse.from(scopedUserForRead(principal, userId));
	}

	@Transactional
	public UserResponse createUser(AuthenticatedPrincipal principal,
			CreateUserRequest request) {
		access.requireOrganizationManagement(principal);
		Organization organization = organizations.findById(principal.organizationId())
				.orElseThrow(() -> new ResourceNotFoundException(
						"ORGANIZATION_NOT_FOUND", "Organization not found"));
		User user = new User(organization, request.email(), request.displayName());
		Set<RoleType> roles = parseRoles(request.roles());
		applyRoles(user, roles);
		User saved = saveOrConflict(user);
		syncMembership(saved, organization, roles);
		if (request.password() != null) {
			// Plaintext at the boundary, BCrypt-hashed immediately by the
			// existing credential service — never stored or returned.
			credentials.setPassword(saved.getId(), request.password());
		}
		return UserResponse.from(saved);
	}

	@Transactional
	public UserResponse updateUser(AuthenticatedPrincipal principal, UUID userId,
			UpdateUserRequest request) {
		User user = scopedUserForManagement(principal, userId);
		user.setDisplayName(request.displayName());
		Set<RoleType> roles = parseRoles(request.roles());
		applyRoles(user, roles);
		User saved = users.save(user);
		syncMembership(saved, user.getOrganization(), roles);
		return UserResponse.from(saved);
	}

	@Transactional
	public UserResponse activateUser(AuthenticatedPrincipal principal, UUID userId) {
		User user = scopedUserForManagement(principal, userId);
		user.setStatus(UserStatus.ACTIVE);
		return UserResponse.from(users.save(user));
	}

	@Transactional
	public UserResponse deactivateUser(AuthenticatedPrincipal principal, UUID userId) {
		User user = scopedUserForManagement(principal, userId);
		user.setStatus(UserStatus.INACTIVE);
		return UserResponse.from(users.save(user));
	}

	private User scopedUserForRead(AuthenticatedPrincipal principal, UUID userId) {
		User user = users.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException(CODE, "User not found"));
		access.requireSelfOrManagement(principal, userId);
		// Management visibility needs the same organization (ADR-040);
		// self reads need none — a platform-level customer always sees
		// their own record. User ids are globally unique, so self-reads
		// never collide across organizations.
		if (!principal.userId().equals(userId)
				&& (user.getOrganization() == null
						|| !user.getOrganization().getId().equals(principal.organizationId()))) {
			throw new ResourceNotFoundException(CODE, "User not found");
		}
		return user;
	}

	private User scopedUserForManagement(AuthenticatedPrincipal principal, UUID userId) {
		User user = users.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException(CODE, "User not found"));
		// Writes need same-organization management visibility (ADR-040);
		// customers reach the management rule (403) without an
		// organization equality detour.
		if (principal.isOperational()
				&& (user.getOrganization() == null
						|| !user.getOrganization().getId().equals(principal.organizationId()))) {
			throw new ResourceNotFoundException(CODE, "User not found");
		}
		access.requireOrganizationManagement(principal);
		return user;
	}

	private void applyRoles(User user, Set<RoleType> roles) {
		for (RoleType current : Set.copyOf(user.getRoles())) {
			user.revokeRole(current);
		}
		for (RoleType role : roles) {
			user.grantRole(role);
		}
	}

	/**
	 * Keeps the organization membership in step with the granted roles
	 * (ADR-040): the membership row is the authorization fact, so an
	 * operational grant must have one in the same organization and a
	 * customer-only account must not. The role is the highest-privilege
	 * operational grant (ADMIN > MANAGER > STAFF); the unique
	 * (user, organization) makes this idempotent per organization.
	 */
	private void syncMembership(User user, Organization organization, Set<RoleType> roles) {
		MembershipRole membershipRole = highestOperationalRole(roles);
		if (membershipRole == null) {
			memberships.findByUserIdAndOrganizationId(user.getId(), organization.getId())
					.ifPresent(existing -> {
						existing.setStatus(MembershipStatus.INACTIVE);
						memberships.save(existing);
					});
			return;
		}
		memberships.findByUserIdAndOrganizationId(user.getId(), organization.getId())
				.ifPresentOrElse(existing -> {
					existing.setRole(membershipRole);
					existing.setStatus(MembershipStatus.ACTIVE);
					memberships.save(existing);
				},
						() -> memberships.save(new OrganizationMembership(user, organization,
								membershipRole)));
	}

	private MembershipRole highestOperationalRole(Set<RoleType> roles) {
		if (roles.contains(RoleType.ADMIN)) {
			return MembershipRole.ADMIN;
		}
		if (roles.contains(RoleType.MANAGER)) {
			return MembershipRole.MANAGER;
		}
		if (roles.contains(RoleType.STAFF)) {
			return MembershipRole.STAFF;
		}
		return null;
	}

	/**
	 * Parses role names; a missing list defaults to {@code CUSTOMER} — the
	 * least privilege for a fresh account. Every role is organization-
	 * scoped by architecture, so granting {@code ADMIN} here creates an
	 * organization admin, never a platform one.
	 */
	private Set<RoleType> parseRoles(List<String> roles) {
		if (roles == null) {
			return EnumSet.of(RoleType.CUSTOMER);
		}
		Set<RoleType> parsed = EnumSet.noneOf(RoleType.class);
		for (String role : roles) {
			try {
				parsed.add(RoleType.valueOf(role.trim().toUpperCase()));
			} catch (IllegalArgumentException e) {
				throw new InvalidUserRequestException("Unknown role: " + role
						+ ". Supported: CUSTOMER, STAFF, MANAGER, ADMIN.");
			}
		}
		return parsed;
	}

	private UserStatus parseStatus(String statusFilter) {
		if (statusFilter == null) {
			return null;
		}
		try {
			return UserStatus.valueOf(statusFilter.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new InvalidRequestException("INVALID_STATUS",
					"Unsupported status filter: " + statusFilter
							+ ". Supported: ACTIVE, INACTIVE.");
		}
	}

	private RoleType parseRole(String roleFilter) {
		if (roleFilter == null) {
			return null;
		}
		try {
			return RoleType.valueOf(roleFilter.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new InvalidRequestException("INVALID_ROLE",
					"Unsupported role filter: " + roleFilter
							+ ". Supported: CUSTOMER, STAFF, MANAGER, ADMIN.");
		}
	}

	/**
	 * Persists with the global email unique constraint as the arbiter: no
	 * check-then-insert (which races), just the flush inside the try so a
	 * duplicate address surfaces here as a stable {@code 409}.
	 */
	private User saveOrConflict(User user) {
		try {
			return users.saveAndFlush(user);
		} catch (DataIntegrityViolationException e) {
			throw new UserEmailConflictException("Email is already in use");
		}
	}
}

