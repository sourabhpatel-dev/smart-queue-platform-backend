package com.sourabh.smartqueue.auth;

import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.membership.MembershipStatus;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueStatus;
import com.sourabh.smartqueue.staff.StaffAssignmentRepository;
import com.sourabh.smartqueue.staff.StaffAssignmentStatus;
import com.sourabh.smartqueue.user.RoleType;

/**
 * Reusable authorization boundary (see ADR-021, ADR-040).
 *
 * <p>Application services and message interceptors call these policies
 * instead of scattering role checks. Rules, in order:
 *
 * <ol>
 *   <li><strong>Organization first.</strong> Nothing crosses organizations —
 *   an authenticated operational principal operates only within the
 *   organization resolved from an ACTIVE
 *   {@link OrganizationMembership}; a principal without a resolved
 *   organization (a customer, or a user whose memberships are all
 *   INACTIVE) is denied organization-scoped operations outright. Role
 *   never substitutes for tenant scope.</li>
 *   <li><strong>Branch belongs to the organization.</strong> A branch id
 *   from another organization is denied even before role evaluation.</li>
 *   <li><strong>Branch access by role.</strong> {@code MANAGER} and
 *   {@code ADMIN} are organization-wide; {@code STAFF} requires an
 *   {@code ACTIVE} {@code StaffAssignment} at that branch (organization
 *   membership alone grants nothing); anyone else (customers) may observe
 *   their own organization's branch displays, whose payloads carry tokens
 *   only and no personal data.</li>
 * </ol>
 *
 * <p>Phase 3H adds the management tier (see ADR-023): creating, updating,
 * activating, or deactivating a branch requires {@code MANAGER} or
 * {@code ADMIN} within the caller's own organization. {@code STAFF} and
 * customers are denied even at their assigned/home branches — reading a
 * display is not managing it. {@code ADMIN} stays organization-scoped,
 * never platform-wide; there is no {@code PLATFORM_ADMIN} (ADR-040).
 */
@Service
public class TenantAccessPolicy {

	private final BranchRepository branches;
	private final StaffAssignmentRepository assignments;
	private final OrganizationMembershipRepository memberships;

	public TenantAccessPolicy(BranchRepository branches,
			StaffAssignmentRepository assignments,
			OrganizationMembershipRepository memberships) {
		this.branches = branches;
		this.assignments = assignments;
		this.memberships = memberships;
	}

	/**
	 * @throws AccessDeniedException unless the principal holds an ACTIVE
	 * membership at the given organization. Denies customers (no
	 * organization) and users whose membership at that organization is
	 * missing or INACTIVE — the membership row, not the legacy
	 * {@code app_user.organization_id} column, is the authorization fact
	 * (ADR-040).
	 */
	public void requireOrganizationAccess(AuthenticatedPrincipal principal, UUID organizationId) {
		if (!principal.hasOrganization()
				|| !principal.organizationId().equals(organizationId)
				|| !memberships.existsByUserIdAndOrganizationIdAndStatus(
						principal.userId(), organizationId, MembershipStatus.ACTIVE)) {
			throw new AccessDeniedException(
					"insufficient authorization for organization " + organizationId);
		}
	}

	/**
	 * @throws AccessDeniedException unless the principal may operate at the
	 * given branch (organization match, branch ownership, role rule).
	 */
	@Transactional(readOnly = true)
	public void requireBranchAccess(AuthenticatedPrincipal principal, UUID branchId) {
		Branch branch = branches.findById(branchId)
				.orElseThrow(() -> new AccessDeniedException(
						"insufficient authorization for branch " + branchId));
		requireOrganizationAccess(principal, branch.getOrganization().getId());
		if (principal.hasAnyRole(RoleType.MANAGER, RoleType.ADMIN)) {
			return;
		}
		if (principal.hasAnyRole(RoleType.STAFF)
				&& assignments.existsByUserIdAndBranchIdAndStatus(
						principal.userId(), branchId, StaffAssignmentStatus.ACTIVE)) {
			return;
		}
		if (!principal.hasAnyRole(RoleType.STAFF)) {
			return;
		}
		throw new AccessDeniedException(
				"insufficient authorization for branch " + branchId);
	}

	/**
	 * @throws AccessDeniedException unless the principal holds a
	 * management role ({@code MANAGER} or {@code ADMIN}) <em>and</em> an
	 * ACTIVE membership at the resolved operational organization. The
	 * ACTIVE membership row — not the role alone, not the legacy
	 * {@code app_user.organization_id} column — is what authorizes
	 * management (ADR-040). The principal can only ever manage its own
	 * organization, never another's, and a principal with no organization
	 * (or only INACTIVE memberships) manages nothing at all.
	 */
	public void requireOrganizationManagement(AuthenticatedPrincipal principal) {
		if (!principal.hasOrganization()
				|| !principal.hasAnyRole(RoleType.MANAGER, RoleType.ADMIN)
				|| !memberships.existsByUserIdAndOrganizationIdAndStatus(
						principal.userId(), principal.organizationId(),
						MembershipStatus.ACTIVE)) {
			throw new AccessDeniedException(
					"insufficient authorization to manage organization "
							+ principal.organizationId());
		}
	}

	/**
	 * @throws AccessDeniedException unless the principal may manage the
	 * given branch: organization scope and branch ownership first (via
	 * {@link #requireBranchAccess}), then the management-role rule.
	 */
	@Transactional(readOnly = true)
	public void requireBranchManagement(AuthenticatedPrincipal principal, UUID branchId) {
		requireBranchAccess(principal, branchId);
		requireOrganizationManagement(principal);
	}

	/**
	 * Operational branch visibility for staff rosters (see ADR-025).
	 *
	 * <p>Like {@link #requireBranchAccess} minus the customer display
	 * pass-through: managers/admins are organization-wide, {@code STAFF}
	 * needs an {@code ACTIVE} assignment at that branch, and customers —
	 * who may observe token-only queue displays — may not enumerate who
	 * works where. Unassigned staff are denied like customers.
	 */
	@Transactional(readOnly = true)
	public void requireBranchOperationalAccess(AuthenticatedPrincipal principal,
			UUID branchId) {
		requireBranchAccess(principal, branchId);
		if (principal.hasAnyRole(RoleType.MANAGER, RoleType.ADMIN)) {
			return;
		}
		if (principal.hasAnyRole(RoleType.STAFF)
				&& assignments.existsByUserIdAndBranchIdAndStatus(
						principal.userId(), branchId, StaffAssignmentStatus.ACTIVE)) {
			return;
		}
		throw new AccessDeniedException(
				"insufficient authorization for branch roster " + branchId);
	}

	/**
	 * Self-or-manager reads (see ADR-025): the principal reading its own
	 * user record or assignment list, otherwise the management rule.
	 * Organization scope is checked by callers first (self implies same
	 * organization — user ids are globally unique).
	 */
	public void requireSelfOrManagement(AuthenticatedPrincipal principal, UUID userId) {
		if (principal.userId().equals(userId)) {
			return;
		}
		requireOrganizationManagement(principal);
	}

	/**
	 * Authorizes a queue-snapshot subscription or snapshot request: the
	 * queue must live at exactly the requested organization/branch scope,
	 * and the principal must hold branch access there.
	 *
	 * <p><strong>ADR-040 customer rule:</strong> a platform-level customer
	 * (CUSTOMER role, no resolved organization) may subscribe to any
	 * <em>ACTIVE</em> queue's topic: the destination names the queue, the
	 * caller already proved the queue exists and lives exactly at the
	 * requested scope, and the payload carries tokens/priority/status/
	 * waits only — no customer identity (ADR-018). Queue state is not
	 * authorization; the resource check is. Operational principals and
	 * non-customer roles without an organization are denied here — they
	 * need their membership-resolved scope.
	 *
	 * @throws AccessDeniedException on any mismatch.
	 */
	@Transactional(readOnly = true)
	public void requireQueueScopeAccess(AuthenticatedPrincipal principal,
			UUID organizationId, UUID branchId, Queue queue) {
		if (!queue.getOrganization().getId().equals(organizationId)
				|| !queue.getBranch().getId().equals(branchId)) {
			throw new AccessDeniedException("queue is outside the requested scope");
		}
		if (!principal.hasOrganization() && !principal.isOperational()) {
			// Customer: ACTIVE queue at the requested scope only.
			if (queue.getStatus() != QueueStatus.ACTIVE) {
				throw new AccessDeniedException("queue is not subscribable");
			}
			return;
		}
		requireOrganizationAccess(principal, organizationId);
		requireBranchAccess(principal, branchId);
	}
}

