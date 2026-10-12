-- V11__notification_retention_index.sql - Retention sweep support (Phase 4E).
--
-- The scheduled cleanup deletes by creation-time predicates across all
-- tenants (`read_at IS [NOT] NULL AND created_at < cutoff`). The V10
-- indexes both lead with `recipient_user_id`, so neither serves a
-- global timestamp sweep — without this index every tick would
-- sequential-scan the whole table. A single `created_at` btree serves
-- both predicates' range condition (the `read_at` nullness is a cheap
-- filter on the range output). See ADR-033.
--
-- Decisions recorded here:
-- - One plain index, not two partial ones: the range selectivity comes
--   from `created_at` in both cases; partial variants would double the
--   write overhead for no meaningful plan gain at this table's shape.
-- - No change to V10 or any earlier migration (project convention:
--   migrations are immutable once applied).

CREATE INDEX idx_notification_created_at
    ON notification (created_at);
