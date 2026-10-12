# ADR-029 — Customer Appointment & Queue Experience

Status: Accepted (Phase 4A, 2026-10-07).

## Context

Phase 3N delivered the authenticated shell with customer placeholder
routes (`/appointments`, `/queue/join`, `/queue/me`). Phase 4A replaces
them with real customer workflows against the unchanged backend. Four
questions are genuinely new: how booking works without an availability
endpoint, how `/queue/me` survives refresh without a "my entries" API,
whether the anonymous realtime snapshot is safe for customer screens, and
how branch-local times round-trip without a date library.

## Decision

1. **No availability calendar is faked.** The backend exposes no
   availability search (documented omission in `docs/api/appointments.md`),
   so the booking form collects branch → service → branch-local start and
   lets the backend validate hours/holidays/conflicts authoritatively.
   Refusals (`APPOINTMENT_CONFLICT`, `OUTSIDE_WORKING_HOURS`,
   `BRANCH_CLOSED`) surface verbatim with actionable copy.
2. **Branch-zone times via `Intl`, no date library.** Display uses
   `Intl.DateTimeFormat` with the response's `branchTimeZone`/`timeZone`
   (date + time + zone together, never the browser zone). Booking converts
   wall time → instant by resolving the zone offset iteratively (two
   passes, DST-safe) and preserves it in the ISO-8601 body. Proven by
   fixed-offset and DST round-trip tests.
3. **`/queue/me` remembers an id, never state.** `localStorage` holds only
   `{entryId, queueId, branchId, organizationId, tokenNumber, serviceName}`.
   Every fact on screen comes from `GET /queue-entries/{entryId}`;
   terminal/vanished entries render their honest states. Customers never
   send `customerId`; triage priorities are not offered (backend 403s
   them); the queue snapshot/detail endpoints stay staff-only and are
   never called.
4. **Realtime as refresh signal, not source of truth.** The 3C snapshot is
   customer-anonymous (tokens/waits only) and tenant-scoped, so
   subscribing weakens nothing. A dependency-free STOMP client (native
   WebSocket, CONNECT/SUBSCRIBE/snapshot-SEND, `?token=` handshake per the
   documented contract) triggers a REST re-read per snapshot and adds the
   anonymous waiting count. Degradation is explicit: "Live updates
   unavailable" + manual Refresh + 30 s REST polling fallback. Redis is
   never touched from the browser.
5. **Null waits stay null.** `waitMinutes: null` renders "Not currently
   available", never `0`; terminal positions render as final states, never
   as line places.

## Alternatives considered

- **Backend "my active entries" endpoint:** the honest REST-native
  solution, but adding endpoints is forbidden by the phase scope; the
  id-only storage achieves refresh survival without it.
- **`@stomp/stompjs` dependency:** unjustified weight for three frames and
  one inbound frame type; the 60-line client is fully covered by tests
  with a fake socket.
- **Polling only:** simpler, but wastes the privacy-safe realtime channel
  the backend already maintains; the signal-plus-fallback design keeps
  REST authoritative either way.

## Consequences

- Customer dashboard/list/detail/book/cancel and queue join/status all
  work against the existing 56 endpoints; no backend change was needed.
- 43 new Vitest cases (76 total frontend) pin contracts, timezone
  preservation, privacy (no identity params), terminal/unknown states,
  and realtime/degradation behavior.
- Known limitation: multi-entry customers see only their latest entry
  (one stored id) — a future "my entries" endpoint would lift this
  without changing the storage contract.
