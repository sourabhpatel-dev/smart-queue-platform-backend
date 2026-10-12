# ADR-039 — Appointment Availability Read Model

Status: Accepted (Phase 4M).

## Context

Phase 4J established that booking was blind: the customer picked a
free date/time and learned about closures/conflicts only from
backend rejection. A read-only availability contract was required
so the future slot-picker can render bookable starts without
reconstructing scheduling rules client-side.

## Decision

**`GET /api/v1/branches/{branchId}/appointments/availability?serviceId=…&date=YYYY-MM-DD`**
returns an advisory, chronologically ordered slot list for the
caller's own booking. Rules exactly mirror booking: branch/service/
customer active, same-org, holiday veto, whole-interval fit inside
one working-hours interval (splits never merged), strict-future
start (`startAt.isAfter(now)`, same rule as `AppointmentService.book`),
and half-open `[start, end)` overlap against the caller's live
(`SCHEDULED`/`CONFIRMED`) service and customer appointments — terminal
rows never block. One-minute candidate steps are derived from the
domain's own minute granularity (working hours reject seconds),
**not** a slot grid: there is no 15/30/60-minute booking increment,
and every bookable minute is listed. No holds, no reservations — a
returned slot may lose a concurrent race; booking re-arbitrates
with the usual 409. Timezone policy is the booking policy: branch
IANA zone, `java.time` gap/overlap semantics with a round-trip check
(nonexistent local starts skipped, ambiguous starts bookable).
Read-only: two bounded day-window queries plus in-memory filtering;
no cache, no new index, no writes.

Response echoes response-scoped facts (branch id, service id,
branch-local date, IANA zone, duration, holiday name/date or null,
verbatim working intervals, slot start/end instants) — enough for a
trustworthy picker without leaking other customers' rows, internal
conflict reasons, or tenant data.

Errors mirror booking: 400 (`INVALID_APPOINTMENT_REQUEST` inactive
branch/service/customer, bad params), 401, 404 (foreign/unknown
branch or service), 422 (`APPOINTMENT_BRANCH_MISMATCH`).

## Alternatives considered

- **Fixed 15/30/60-minute grids** (Models B/C from 4J): rejected —
  invents a configuration the domain does not have; a 20-minute
  service would force lossy starts.
- **Continuous free-time intervals response:** rejected for v1 —
  harder for a picker to render than discrete starts; the minute-step
  list is equivalent information at this scale (≤ ~10h/day).
- **Public/unauthenticated slots:** rejected — capacity utilisation
  is tenant-visible information; keep authenticated like every other
  booking surface.
- **Client-side rule evaluation (ship hours/holidays):** rejected —
  duplicates DST/holiday/overlap logic and can disagree with booking.
- **Redis caching:** rejected for v1 — correctness-first; add later
  only with an explicit TTL/invalidation story.

## Consequences

- One new read endpoint; booking excludes untouched
  (`no_service_double_booking`/`no_customer_double_booking` stay
  authoritative). No migration, no new index beyond the existing
  branch-time and exclusion indexes.
- Availability is advisory: the UI must handle the 409 race on
  submit (already surfaced as a sentence today).
- Known scope: single branch-local day per request (multi-day browse
  is repeated calls); slots never echo per-gap reasons (by design).
