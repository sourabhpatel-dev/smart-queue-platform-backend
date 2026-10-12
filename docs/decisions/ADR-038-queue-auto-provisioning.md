# ADR-038 — Queue Auto-Provisioning on Service Creation

Status: Accepted (Phase 4K).

## Context

The domain invariant is one queue per `(branch, service)`
(`uq_queue_branch_service`, ADR-012), and every queue API assumes the
row exists. But no production code path created queue rows: `new
Queue(` appeared only in tests and `demo-seed.sql`. A service created
through `POST /api/v1/branches/{branchId}/services` therefore had no
walk-in queue, while the branch queue list, snapshots, joins, and
claims all require one. Phase 4J confirmed this as a production gap
(not an intentional manual step): there is deliberately no queue-creation
REST route, so nothing — UI or API consumer — could repair the missing
row.

## Decision

**Auto-provision the queue inside service creation, same transaction;
backfill existing pairs with an idempotent migration.**

- `ServiceManagementService.createService` (the single production
  service-creation path — verified by search) provisions the `ACTIVE`
  queue via the new `queue.application.QueueProvisioningService`
  seam before returning. Default `REQUIRED` propagation joins the
  existing creation transaction: service + queue commit together or
  roll back together. There is never a committed service without its
  queue.
- The seam is find-or-create: an existing row for the pair is
  returned untouched (id, status, and history preserved — an
  `INACTIVE` queue is never reactivated or duplicated by a repeated
  call). The `uq_queue_branch_service` unique constraint stays the
  final backstop.
- No catch-and-continue around the insert. The pair references a
  just-flushed fresh service id, so a uniqueness conflict is
  unreachable through this path (concurrent same-name service
  creation is arbitrated earlier by the service-name unique); any
  genuine persistence failure must roll the creation back rather
  than leave a queueless service. This also respects the platform
  rule that PostgreSQL aborts the whole transaction on a constraint
  violation — catching one could never usefully continue.
- No status mirroring and no cascading: the queue keeps its own
  `ACTIVE` default as the walk-in kill-switch. `Branch ACTIVE +
  Service INACTIVE + Queue ACTIVE` and the reverse remain legal
  states; joining is rejected by branch/service/queue checks at join
  time (unchanged).
- No queue CRUD, no queue endpoints, no service-response change
  (no queue id embedded), no frontend work in this phase. The
  cross-module seam (service application → queue application
  service, not repository) is the documented exception to the
  no-cross-module-reach rule: queue rows are owned by the queue
  module, and this seam is the single defined writer outside tests
  and seeds.
- V19 backfills pre-existing pairs (`INSERT … SELECT … WHERE NOT
  EXISTS … ON CONFLICT DO NOTHING`): missing rows created `ACTIVE`,
  existing rows never touched, re-runnable to zero rows.

## Alternatives considered

- **Explicit queue CRUD (manager-created queues):** rejected — the
  one-per-pair cardinality leaves nothing to configure; CRUD would
  add a management surface for a derived row and invite cardinality
  violations the unique constraint would then have to refuse.
- **Lazy creation on first join/list:** rejected — reads would gain
  write side effects (breaking the read-only snapshot/list contract
  and the Redis-independent-correctness proof), and concurrent first
  joins would race the insert on the hot path instead of settling
  it once at configuration time.
- **Nested-transaction insert with conflict retry:** rejected —
  savepoint machinery for a race the fresh-id ordering makes
  unreachable; the constraint backstop plus the backfill cover the
  real cases.
- **Mirroring service status onto the queue:** rejected — destroys
  the independent kill-switch (pausing walk-ins while keeping
  appointments requires exactly the split states above).

## Consequences

- `POST …/services` gains the invariant "the queue now exists"
  with no contract change (same `201` + `Location`, same body).
  Existing queue UI starts working for newly created services.
- `V19__backfill_service_queues.sql` (with `pgcrypto` extension
  guard, mirroring the V5 `btree_gist` pattern) establishes the
  invariant for old rows; `gen_random_uuid()` ids, `now()` stamps.
- `demo-seed.sql` keeps its explicit queue inserts: the seed bypasses
  the application layer by raw SQL, so auto-provisioning never fires
  for it, and seeded entries/sessions reference those explicit ids.
- Tests: `QueueProvisioningTests` (seam, independence, isolation,
  idempotent reuse, DB-level race arbitration, backfill semantics,
  REST list/join/call-next end-to-end) plus
  `QueueProvisioningRollbackTests` (provisioning failure rolls the
  service creation back via a test-only throwing seam).
- Known pre-existing failures elsewhere (calendar/analytics/reminder/
  notification suites — shared-transaction poisoning and fixture
  constraint bugs, none on paths this phase touches) are recorded in
  the Phase 4K report, not fixed here.
