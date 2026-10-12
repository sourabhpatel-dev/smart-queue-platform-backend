# ADR-031 — Manager & Admin Experience

Status: Accepted (Phase 4C, 2026-10-07).

## Context

Phases 4A/4B delivered customer and staff workflows; management routes
were placeholders. Phase 4C turns the existing management APIs
(branches 3H, services/counters/capabilities 3I, users/assignments 3J)
into a manager/admin workspace without touching the backend. Six
questions are genuinely new: what the overview may honestly show, how
branch-nested collections are browsed, where editing lives versus
dialogs, how capabilities and assignments stay truthful to backend
invariants, when confirmations are required, and how tables stay
readable on small screens.

## Decision

1. **Overview shows counts, not KPIs.** Branch and people totals come
   from first-page `totalElements`; configuration cards carry
   navigation. No revenue/utilization/SLA/wait-time figures exist in
   any consumed endpoint, so none are shown.
2. **Branch-nested browsing, flat item routes.** Services and counters
   are picked per branch (branch picker fed by the real directory,
   persisted in the query string); item pages live at flat
   `/manage/services/:id` and `/manage/counters/:id`, matching the
   backend's nested-collection/flat-item split.
3. **Dialogs for creates, pages for edits.** Quick-create dialogs cover
   services, counters, capabilities, and assignments (one or two
   fields); full branch/user forms are pages. Lifecycle commands that
   deactivate always confirm with consequence-specific copy; plain
   navigation/editing never confirms.
4. **Backend arbitrates; UI translates.** Duplicate names (409),
   cross-branch capability/assignment links (422), and the STAFF-role
   assignment requirement surface as plain sentences from stable
   codes (`management/utils/errors.ts`); 400 `fieldErrors` render
   per-field. No client-side duplicate authority is claimed.
5. **Credentials never round-trip.** Create-user sends an optional
   initial password once and clears the field; the response has no
   credential fields by construction, and a test asserts none is
   sent back. Email is shown immutable; role changes confirm because
   removing STAFF revokes branch access at once.
6. **Tables with card fallbacks, skeletons for loads.** Desktop tables
   (few columns, status badges, row actions) collapse to cards under
   860 px; table loads render skeleton rows, never a full-page
   spinner for one table. Reduced-motion preferences disable shimmer
   and transitions.

## Alternatives considered

- **Analytics/KPI dashboard:** rejected — no backend endpoint
  provides authoritative aggregates; a polished empty overview beats
  fake charts (explicit phase requirement).
- **Modal-for-everything editing:** rejected — branch/user forms carry
  enough fields and validation to deserve pages with breadcrumbs.
- **Client-side search over pages:** rejected — filtering/sorting stay
  server-side; search over partial pages would lie by omission.
- **New state library:** rejected — React state + focused fetch hooks
  suffice; no store was introduced (consistent with ADR-028).

## Consequences

- Thirteen management routes (overview, branches list/new/detail/
  edit, services list/detail, counters list/detail, staff
  list/new/detail/edit) work for MANAGER/ADMIN against existing
  endpoints; CUSTOMER and STAFF are denied at every `/manage` route
  (UX redirect — backend enforces); zero backend changes; 17 new
  frontend tests (130 total); production build green.
- Known gap: `/queue/me`-style deep operational oversight (org-wide
  appointments/queues) stays under Operations; working-hours/holiday
  management has no backend API and therefore no UI.
