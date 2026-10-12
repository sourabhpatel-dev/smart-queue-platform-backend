# ADR-033 — Notification Retention & Cleanup

Status: Accepted (Phase 4E, 2026-10-07).

## Context

Phase 4D made notifications persistent with no removal path: the
`notification` table grows with every booking, join, call, completion,
cancellation, and no-show. A production table needs a bounded,
explicit, tenant-safe cleanup — but nothing more: no reminders, no
preferences, no archival, no job framework.

## Decision

1. **Two horizons, one age anchor.** Read rows are eligible once
   `created_at` is older than `read-after` (default P90D); unread rows
   once older than `unread-after` (default P365D). Read rows served
   their purpose; unread rows stay far longer so a lapsed user still
   finds them. `created_at` anchors both — the notification is a
   historical user-facing record, so its creation time is the honest
   age. Not `updated_at` (mark-read would extend life arbitrarily),
   not `read_at` (unread rows have none), not event time (the row is
   what ages). Eligibility is strict `< cutoff`; boundary rows are kept.
2. **Validated static configuration.** `notifications.retention`
   (`enabled`, `read-after`, `unread-after`, `interval`, default PT6H)
   follows the `QueueRealtimeRefreshProperties` style: ISO-8601
   durations, `@AssertTrue` positivity, fail fast at startup,
   disableable via `enabled: false` (bean vanishes; creation/reads/
   realtime unaffected). Overridable as `NOTIFICATIONS_RETENTION_*`.
3. **Id-batched bulk deletes, no hydration.** The sweep pages eligible
   ids oldest-first (500 per batch, ids only) and deletes by id list,
   repeating while a full batch returns. No entity loading, no N+1, no
   Redis, no WebSocket, no REST, no events, no new notifications. The
   timestamp predicate needs no tenant data — cleanup is a global
   maintenance operation and deletes touch only eligible rows.
4. **Per-batch transactions, independent from creation.** Each batch
   commits in its own transaction (TransactionTemplate); the tick holds
   no tick-wide transaction and never joins user or creation flows.
   Committed batches stay deleted if a later batch fails; the next
   tick retries the remainder. A row read mid-sweep simply moves to
   the other predicate's next pass — deletion is idempotent either way.
5. **Best-effort scheduling, Phase 3D style.** Fixed-delay tick (no
   self-overlap on one instance), summary logging (cutoffs + counts,
   never contents), failures caught and logged with retry on the next
   tick, never thrown outward. Two instances may sweep concurrently —
   accepted and documented: both delete from committed state and the
   bulk delete is naturally idempotent.
6. **No distributed lock, no framework.** Janitorial work every six
   hours does not justify Quartz, ShedLock, or a job framework — the
   same call Phase 3D made for best-effort derived state.
7. **One supporting index.** V11 adds `idx_notification_created_at`:
   both V10 indexes lead with `recipient_user_id` and cannot serve a
   global timestamp sweep; one `created_at` btree serves both
   predicates' range (read-state is a cheap filter). No partial-index
   pair — double write overhead for no plan gain at this shape.
8. **No user-facing surface.** Removed rows vanish from lists and read
   as scope-safe 404s by id. No "expired" state, no retention metadata,
   no delete endpoint, no settings UI — retention is an operational
   policy, not a user feature.

## Alternatives considered

- **Unbounded single DELETE per predicate:** rejected — one massive
  statement/transaction on a large backlog; the id-batch loop bounds
  statement size and memory with trivial code.
- **Entity load + `deleteAll`:** rejected — hydrates rows about to
  disappear and risks persistence-context bloat; bulk delete is the
  production shape.
- **Retention anchored on `read_at`:** rejected — unread rows have no
  stamp and reads would extend life; `created_at` is uniform.
- **Archival table:** rejected — speculative infrastructure with no
  consumer; rows are simply deleted.
- **Per-tenant sweeping:** rejected — the predicate needs no tenant
  data; tenant-scoped queries would add round-trips for zero safety
  gain.

## Consequences

- `NotificationRetentionProperties`, repository id-page + bulk-delete
  queries, `NotificationRetentionService` (tick + purge), V11 index,
  `application.yml` block; 15 new backend tests (7 properties, 6
  behavior, 2 disabled); zero frontend changes; no new dependencies.
- Known gaps: no reminders, no preferences, no archival, no
  cross-instance coordination claims, no exactly-once execution claims.
