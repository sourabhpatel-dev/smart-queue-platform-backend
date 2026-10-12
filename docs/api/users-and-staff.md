# Users & Staff API (Phase 3J)

Organization user directory and branch staffing links (decided in
[ADR-025](../decisions/ADR-025-user-staff-assignment-api.md)). Generic
conventions — envelope, versioning, DTOs, JSON shapes — live in
[`rest-foundation.md`](rest-foundation.md) and are not repeated here.
Authentication itself is unchanged from Phase 3F.

## URL shape

```text
GET    /api/v1/users[?page&size&sort&status&role]
POST   /api/v1/users
GET    /api/v1/users/{userId}
PUT    /api/v1/users/{userId}
POST   /api/v1/users/{userId}/activate
POST   /api/v1/users/{userId}/deactivate

GET    /api/v1/users/{userId}/staff-assignments[?page&size&sort&status]
POST   /api/v1/users/{userId}/staff-assignments   {"branchId": "…"}
GET    /api/v1/branches/{branchId}/staff[?page&size&sort&status]

GET    /api/v1/staff-assignments/{assignmentId}
POST   /api/v1/staff-assignments/{assignmentId}/activate
POST   /api/v1/staff-assignments/{assignmentId}/deactivate
```

## Authorization matrix

| Operation | CUSTOMER | STAFF | MANAGER | ADMIN |
|---|---|---|---|---|
| List users / read other users | ❌ 403 | ❌ 403 | ✅ | ✅ |
| Read own user record | ✅ (self) | ✅ (self) | ✅ | ✅ |
| Create/update/activate/deactivate users | ❌ 403 | ❌ 403 | ✅ | ✅ |
| List own assignments | ✅ (self) | ✅ (self) | ✅ | ✅ |
| List others' assignments | ❌ 403 | ❌ 403 | ✅ | ✅ |
| Branch roster | ❌ 403 | ✅ assigned branch | ✅ | ✅ |
| Create/activate/deactivate assignments | ❌ 403 | ❌ 403 | ✅ | ✅ |

The directory is management data (emails are personal data — no customer
enumeration, no staff-wide listing). Two visibility tiers beyond the 3H
model: operational roster access (managers/admins + assigned staff;
customers denied, unlike token-only queue displays) and self-or-manager
reads (own record, own assignments). Managers/admins stay
organization-scoped — ADMIN is an organization admin, never platform-wide
(proven: a fresh ADMIN cannot see the other organization). Foreign ids
are scope-safe `404`s throughout.

## Users

- Response: `id`, `organizationId`, `email` (normalized), `displayName`,
  `roles` (sorted names), `status`, timestamps. Never credentials.
- Create: `email`, `displayName`, optional `roles` (default
  `[CUSTOMER]`), optional plaintext `password` (8–72 chars: the BCrypt
  truncation cap inherited from login, plus a documented 8-char floor).
  The password is BCrypt-hashed immediately by the existing credential
  service — never stored or returned. Omitting it leaves a
  credential-less user that fails login generically. → `201` + `Location`.
- Email is identity: set once, immutable afterwards (supplied values on
  PUT are ignored). Globally unique by constraint — duplicates
  (case-folded) yield `409 USER_EMAIL_CONFLICT`, constraint-arbitrated.
- Update: PUT full replacement of `displayName` + complete role set (may
  be empty). No email/org/credential/status fields, no password workflow,
  no self-service editing (deferred openly, not missing).
- Lifecycle: idempotent activate/deactivate (`200`). Deactivation
  preserves appointments, queue history, assignments, and audit rows;
  login fails generically while inactive (Phase 3F rule, regression-
  tested end to end: login → deactivate → denied → reactivate → login).
- List: `page`/`size` (20/100), sort allow-list
  `email,displayName,status,createdAt,updatedAt` + `id` tiebreak, optional
  `status` and `role` filters (`400 INVALID_STATUS` / `INVALID_ROLE`).

## Staff assignments

- The existing `StaffAssignment` model, unchanged: same-organization
  user+branch pinned by the composite foreign keys (backstop), unique
  `(user, branch)` ignoring status, `ACTIVE`/`INACTIVE` lifecycle.
- Create requires the user to hold the `STAFF` role (assignments for
  other roles would be rows the policy never consults — rejected with
  `400`, not stored). Cross-organization pairs are scope-safe `404`s;
  same-org user/branch mismatch cannot occur through this API (both legs
  are principal-scoped first) but stays guarded as `422`.
- Re-adding an existing row yields `409 STAFF_ASSIGNMENT_CONFLICT`
  (reactivate instead — even INACTIVE rows conflict by design).
- Removal deletes nothing: commands flip status only; user, branch, and
  history stay intact. Deactivating a STAFF holder's assignment revokes
  that branch's access immediately (proven live); reactivation restores
  it with no second row.
- Roster rows carry user identity plus both statuses (`assignmentId`,
  `userId`, `email`, `displayName`, `roles`, `userStatus`,
  `assignmentStatus`, `assignedAt`) — one call, no credentials.

## Error codes (this surface)

`USER_NOT_FOUND`, `USER_EMAIL_CONFLICT`, `INVALID_USER_REQUEST`,
`STAFF_ASSIGNMENT_NOT_FOUND`, `STAFF_ASSIGNMENT_CONFLICT`,
`STAFF_ASSIGNMENT_BRANCH_MISMATCH` (422), `INVALID_ROLE`, plus the
shared `VALIDATION_ERROR`, `INVALID_PAGE/SIZE/SORT/STATUS`,
`UNAUTHENTICATED`, `FORBIDDEN`, `BRANCH_NOT_FOUND` codes.

## Intentional omissions

No user deletion, no organization transfer, no email change, no
password-change/reset workflow, no self-service profile editing, no
platform admin role, no multi-organization users, no role sorting.
