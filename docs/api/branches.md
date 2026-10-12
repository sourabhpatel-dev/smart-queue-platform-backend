# Branch API (Phase 3H)

First real management surface (decided in
[ADR-023](../decisions/ADR-023-branch-management-api.md)). Generic
conventions — envelope, versioning, DTOs, JSON shapes — live in
[`rest-foundation.md`](rest-foundation.md) and are not repeated here.

Organization reads stay as documented (`GET /api/v1/organizations/{id}`
— own org only). There is no organization listing and no organization
mutation, by design (tenant isolation; see § organization rules below).

## Authorization matrix

| Operation | CUSTOMER | STAFF | MANAGER | ADMIN |
|---|---|---|---|---|
| Read own organization | ✅ | ✅ | ✅ | ✅ |
| List branches (own org) | ✅ | ✅ | ✅ | ✅ |
| Read branch | ✅ own-org display | ✅ assigned branch | ✅ | ✅ |
| Create branch | ❌ 403 | ❌ 403 | ✅ | ✅ |
| Update branch | ❌ 403 | ❌ 403 | ✅ | ✅ |
| Activate / deactivate | ❌ 403 | ❌ 403 | ✅ | ✅ |

Roles come from the Phase 3F policy: managers/admins are
organization-wide, staff need an ACTIVE assignment **at that branch** for
single-branch reads (listing is organization-scoped for every role),
customers observe their own organization's displays. `ADMIN` is
organization-scoped — never platform-wide. All roles are confined to
their own organization: foreign ids are scope-safe `404`s, never `403`s.

## Endpoints

### `GET /api/v1/branches` → `200`

Own-organization branches, paginated:

```json
{
  "content": [
    {
      "id": "…",
      "organizationId": "…",
      "name": "Downtown",
      "addressLine1": "12 Main St",
      "addressLine2": null,
      "city": "Indore",
      "state": "MP",
      "postalCode": "452001",
      "country": "India",
      "timeZone": "Asia/Kolkata",
      "status": "ACTIVE",
      "createdAt": "2026-10-06T09:00:00Z",
      "updatedAt": "2026-10-06T09:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

Query parameters:

| Param | Default | Rule |
|---|---|---|
| `page` | `0` | 0-based; negative → `400 INVALID_PAGE` |
| `size` | `20` | 1–100; outside → `400 INVALID_SIZE` |
| `sort` | `name,asc` | entries `field[,asc\|desc]`, repeatable; allow-list `name,status,createdAt,updatedAt`; ties break by `id ASC`; anything else → `400 INVALID_SORT` |
| `status` | *(both)* | `ACTIVE` / `INACTIVE` (case-insensitive); anything else → `400 INVALID_STATUS` |

Out-of-range pages succeed with empty `content` (never 404). Inactive
branches are included unless `status` narrows them. Readable by every
role in the organization, including customers.

> Framework note: `sort` values are read raw from the request — Spring's
> `List<String>` binding splits `sort=name,asc` on the comma, corrupting
> the documented shape. Proven by test.

### `GET /api/v1/branches/{branchId}` → `200`

Single branch. Unknown-or-foreign id → `404 BRANCH_NOT_FOUND`
(indistinguishable); same-org but outside the caller's role → `403`.

### `POST /api/v1/branches` → `201`

MANAGER/ADMIN only. Body carries client-controlled fields only
(`name`, flat address, `city`, `state`, `postalCode`, `country`,
`timeZone`) — no `id`, no `organizationId` (derived from the principal;
a supplied value is ignored), no `status` (born `ACTIVE`), no audit
fields. Returns the created view plus a `Location` header
(`/api/v1/branches/{id}`, built from the current request — no hard-coded
hosts). Duplicate name in the organization → `409
BRANCH_NAME_CONFLICT` (constraint-arbitrated, no SQL leaks); the same
name in another organization stays legal.

### `PUT /api/v1/branches/{branchId}` → `200`

Full replacement of the mutable fields (no PATCH variant exists).
Organization ownership and status are immutable here — extra
`organizationId`/`status` properties are ignored, never applied.
Unknown-or-foreign → `404`; role → `403`; duplicate rename → `409`.

### `POST /api/v1/branches/{branchId}/{activate,deactivate}` → `200`

Explicit lifecycle commands (idempotent — repeating the current state
succeeds unchanged). Deactivation is not deletion: services, counters,
assignments, calendar, appointments, and queues are never
cascade-deleted; deactivated branches stay visible to list/get.
MANAGER/ADMIN only; foreign → `404`. There is no `DELETE` endpoint —
branch deletion is intentionally unsupported (documented, not missing).

### `GET /api/v1/branches/{branchId}/schedule-status` → `200`

Read-only branch schedule status. Returns the current operational state
(`OPEN`, `CLOSED`, `HOLIDAY`, or `INACTIVE`), the branch's IANA timezone,
the current instant, today's operating intervals, and the next upcoming
holiday (if any). Readable by every role in the organization.

**State precedence** (first match wins):

1. `INACTIVE` — branch status is `INACTIVE`
2. `HOLIDAY` — today is a configured holiday for this branch
3. `OPEN` — current branch-local time falls within a working interval
4. `CLOSED` — no working interval for today's weekday, or outside hours

Branch-local time is derived from the backend `Instant` and the branch's
configured timezone. Server timezone is never used. Inactive branches
return `INACTIVE` rather than an error.

**Response fields:**

| Field | Type | Description |
|---|---|---|
| `branchId` | UUID | The branch |
| `timezone` | String | IANA timezone (e.g. `Asia/Kolkata`) |
| `now` | Instant | Backend current time (UTC) |
| `state` | String | `OPEN` / `CLOSED` / `HOLIDAY` / `INACTIVE` |
| `todayIntervals` | Array | `{opensAt, closesAt}` for today's weekday |
| `upcomingHoliday` | Object/null | `{date, name}` of next future holiday, or null |
| `reason` | String/null | Human-readable state explanation |

**Error codes:** `BRANCH_NOT_FOUND` (404), `UNAUTHENTICATED` (401),
`FORBIDDEN` (403).

## Error codes (this surface)

`VALIDATION_ERROR`, `INVALID_BRANCH_REQUEST` (bad time zone),
`INVALID_PAGE`, `INVALID_SIZE`, `INVALID_SORT`, `INVALID_STATUS`,
`UNAUTHENTICATED`, `FORBIDDEN`, `BRANCH_NOT_FOUND`,
`BRANCH_NAME_CONFLICT`, `INTERNAL_ERROR` — all in the shared envelope.

## Organization rules (deliberate omissions)

- No `GET /api/v1/organizations` listing (cross-tenant enumeration has no
  use case and contradicts isolation).
- No organization mutation (no administration requirement this phase).
- No branch transfer between organizations (no such field, no such flow).
- No branch deletion (status lifecycle instead).
