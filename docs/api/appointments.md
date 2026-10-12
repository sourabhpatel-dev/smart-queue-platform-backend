# Appointments API (Phase 3K)

Booking and lifecycle over the Phase 2E domain, unchanged (decided in
[ADR-026](../decisions/ADR-026-appointment-booking-api.md)). Generic
conventions — envelope, versioning, DTOs, JSON shapes — live in
[`rest-foundation.md`](rest-foundation.md) and are not repeated here.

> **Identity model note (ADR-040, Phase 4R.3 implemented).** Customers
> are platform-level identities: one account books at multiple ACTIVE
> organizations. The branch in the request path names the organization
> (validated ACTIVE); the customer leg carries no organization equality
> (the branch's organization is the row's). Reads are owner-scoped across
> organizations for customers, organization-scoped for operational roles;
> cancel keeps explicit `403` for non-owner customers and branch
> management; complete/no-show stay operational. Legacy
> `app_user.organization_id` values never authorize.

## URL shape

```text
POST /api/v1/branches/{branchId}/appointments   {"serviceId": "…", "startAt": "…"}
GET  /api/v1/appointments[?page&size&sort&status&branchId&serviceId&customerId&from&to]
GET  /api/v1/appointments/{appointmentId}
POST /api/v1/appointments/{appointmentId}/cancel
POST /api/v1/appointments/{appointmentId}/complete
POST /api/v1/appointments/{appointmentId}/no-show
```

Creation nests under the branch (context explicit); everything else is
flat. One list route only — no branch-nested duplicate.

## Booking

- Body: `serviceId` + ISO-8601 `startAt` (offset preserved as an
  instant). No customer id in any role — the principal is always the
  customer (no delegated booking exists; managers book for themselves
  like everyone else). No status/end/duration/audit fields.
- The branch comes from the path and must own the service: service-at-
  another-branch → `422 APPOINTMENT_BRANCH_MISMATCH` (in any ACTIVE
  organization for customers, same-org only for operational roles);
  unknown ids → `404`.
- Starts must be future (API-boundary rule, project Clock). Everything
  else — working hours, holidays, branch-zone interpretation, DST,
  duration snapshot, overlap arbitration — runs inside the existing
  booking service, untouched. → `201` + `Location`
  (`/api/v1/appointments/{id}`).
- Conflicts (per-service and per-customer exclusions, including
  concurrent races) → `409 APPOINTMENT_CONFLICT` with the slot intact;
  the freed slot of a cancelled row re-books immediately. Calendar
  refusals keep their codes (`OUTSIDE_WORKING_HOURS`, `BRANCH_CLOSED`,
  `INVALID_APPOINTMENT_REQUEST`).

## Authorization matrix

| Operation | CUSTOMER | STAFF | MANAGER | ADMIN |
|---|---|---|---|---|
| Book (self) | ✅ any ACTIVE org | ✅ own org | ✅ own org | ✅ own org |
| Read own appointment | ✅ any org | ✅ (own) | ✅ (own) | ✅ (own) |
| Read others' | ❌ 404 | ✅ assigned branch | ✅ own org | ✅ own org |
| List | own only, all orgs (other id → 403) | assigned `branchId` required | ✅ + facets | ✅ + facets |
| Cancel own | ✅ | ✅ (own) | ✅ (own) | ✅ (own) |
| Cancel others' | ❌ 403 | ❌ 403 | ✅ branch mgmt | ✅ branch mgmt |
| Complete / no-show | ❌ 403 | ✅ assigned branch | ✅ | ✅ |

Item reads are privacy-first: non-owners outside operational scope get
the scope-safe `404`, never a peek at another customer's appointment
(unlike branch reads, where same-org denial is 403 — appointment rows
carry personal identity). Commands deny explicitly (`403`): the action
targets a caller-known resource, so policy — not existence — is the
message. Managers/admins stay organization-scoped; foreign ids are
`404`s both directions.

## Listing

Role-routed as above. Facets (`branchId`, `serviceId`, `customerId`)
are each scope-checked (`404` when foreign); mismatched combinations
simply return empty — no error. `status` parses to the domain enum
(`400` otherwise). Time window (`from`/`to`, ISO-8601) selects
overlapping intervals (`endAt > from AND startAt < to`); `from > to` →
`400 INVALID_TIME_RANGE`. Sort allow-list: `startAt` (default),
`endAt`, `status`, `createdAt`, `updatedAt` + `id` tiebreak. Paginated
20/100 like every list.

## Lifecycle

`cancel` (owner or branch management), `complete` / `no-show`
(operational scope: assigned staff + managers/admins). All call the
guarded domain transitions: terminal rows refuse with `409
APPOINTMENT_INVALID_TRANSITION` — deliberately NOT idempotent (the
domain rule wins over the 3H command convention). No generic
status-write endpoint, no `DELETE`, no `CONFIRMED` flow (the domain has
no confirm transition — not added here). History is never deleted.

## Response

`id`, `organizationId`, `branchId`, `branchTimeZone` (IANA, for
client-side interpretation), `serviceId`, `serviceName`,
`customerId` (every reader is authorized to see it), `startAt`,
`endAt`, snapshotted `durationMinutes`, `status`, timestamps.

## Error codes (this surface)

`APPOINTMENT_NOT_FOUND`, `APPOINTMENT_CONFLICT`,
`APPOINTMENT_INVALID_TRANSITION` (409), `APPOINTMENT_BRANCH_MISMATCH`
(422), `INVALID_TIME_RANGE` (400), plus the shared booking codes and
envelope.

## Intentional omissions

Delegated booking, rescheduling, recurring appointments, waitlists,
reminders, confirm flow, queue check-in integration, staff front-desk
cancellation for others' rows. Availability search existed as a
planned omission through Phase 3K — it shipped in Phase 4M.

## Availability (Phase 4M, ADR-039)

```text
GET /api/v1/branches/{branchId}/appointments/availability
    ?serviceId={uuid}&date={YYYY-MM-DD}
```

Read-only, advisory. `date` is the **branch-local** calendar day.
Response:

```json
{
  "branchId": "…", "serviceId": "…", "date": "2026-10-20",
  "timezone": "Asia/Kolkata", "serviceDurationMinutes": 30,
  "holiday": null,
  "workingIntervals": [
    { "opensAt": "09:00:00", "closesAt": "13:00:00" },
    { "opensAt": "14:00:00", "closesAt": "18:00:00" }
  ],
  "slots": [
    { "start": "2026-10-20T03:30:00Z", "end": "2026-10-20T04:00:00Z" }
  ]
}
```

- A slot is listed only when the full `[start, start+duration)` fits
  inside **one** working interval (splits never merged), the branch
  is not closed for a holiday, the start is strictly in the future,
  and no live (`SCHEDULED`/`CONFIRMED`) appointment for the **same
  service** or the **same customer** overlaps it (half-open bounds —
  boundary-touching neighbors are fine).
- `holiday` is `{date, name}` when the day is a full-day closure,
  and `slots` is then empty. Closed weekdays return empty
  `slots` + empty `workingIntervals`.
- Candidate steps are **one minute**, derived from the domain's own
  minute granularity — there is no fixed 15/30/60-minute grid and no
  configurable increment.
- `slots` are chronological instants; `timezone` is the branch zone
  (`java.time` gap/overlap rules: nonexistent local starts skipped,
  ambiguous starts bookable — identical to booking).
- **Advisory only**: booking re-arbitrates authoritatively and a slot
  may 409 on a concurrent race. No slot holds/reservations.
- Errors: `400 INVALID_APPOINTMENT_REQUEST` (inactive
  branch/service/customer, bad date/service), `401`, `404
  BRANCH_NOT_FOUND`/`SERVICE_NOT_FOUND` (foreign org), `422
  APPOINTMENT_BRANCH_MISMATCH`.
