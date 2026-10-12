-- V19__backfill_service_queues.sql — Queue auto-provisioning backfill (Phase 4K).
--
-- Problem: the one-queue-per-(branch, service) invariant (uq_queue_branch_service,
-- ADR-012) had no production creation path — queue rows existed only via tests and
-- demo seeds, so a newly created service could lack its required walk-in queue
-- while the queue APIs assume it exists. From this version, service creation
-- provisions the queue in the same transaction (see QueueProvisioningService,
-- ADR-038); this migration repairs pre-existing pairs.
--
-- What it creates: one ACTIVE queue row per (branch, service) pair that lacks
-- one. Organization comes from the owning branch (service has no org column —
-- branch does, and the queue FKs require all three legs to agree).
--
-- Why it is idempotent: the WHERE NOT EXISTS skips pairs that already have a
-- queue, and ON CONFLICT (branch_id, service_id) DO NOTHING absorbs the
-- concurrent-insert race (two migrations/seeds racing the same pair). Re-running
-- creates zero rows. Existing queue rows (ids, statuses, token counters,
-- entries, sessions) are never touched — only missing rows are inserted.
--
-- Invariant established afterwards: every service row has exactly one queue row.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO queue (id, organization_id, branch_id, service_id, status, created_at, updated_at)
SELECT gen_random_uuid(), b.organization_id, s.branch_id, s.id, 'ACTIVE', now(), now()
FROM service s
JOIN branch b ON b.id = s.branch_id
WHERE NOT EXISTS (
    SELECT 1 FROM queue q
    WHERE q.branch_id = s.branch_id AND q.service_id = s.id
)
ON CONFLICT (branch_id, service_id) DO NOTHING;
