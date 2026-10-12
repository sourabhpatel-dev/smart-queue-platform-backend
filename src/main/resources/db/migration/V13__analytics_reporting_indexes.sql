-- V13__analytics_reporting_indexes.sql - Operational analytics support (Phase 4G).
--
-- The overview report scopes every aggregate by organization plus an
-- effective [from, to) instant window, with branch/service as
-- additional equalities (see ADR-035). Each index below serves one
-- query predicate shape actually used by AnalyticsRepository — no
-- speculative indexing (project convention: indexes justified by
-- query patterns, never per-column).
--
-- Decisions recorded here:
-- - idx_appointment_analytics (organization_id, start_at): the
--   appointment aggregates filter org equality over a start-time
--   range. Branch/service act as filters on that bounded set (the
--   window is at most the 366-day reporting limit, typically days),
--   so no branch/service-specific variant is added. Branch-filtered
--   reports may equally use the existing idx_appointment_branch_time.
-- - idx_queue_entry_analytics (organization_id, joined_at): same
--   shape for walk-in activity anchored on joinedAt. No queue_id
--   index: entry→queue→branch/service legs resolve against the tiny
--   bounded set (one queue row per branch+service by V7 cardinality).
-- - idx_service_session_branch_started (branch_id, started_at):
--   sessions carry no organization_id (V8 models branch anchoring),
--   so org-wide reports expand to the organization's branches (few
--   rows by the V2 tenancy shape) and scan each branch's started
--   range; branch-filtered reports hit one equality plus the range
--   directly. An organization_id column on the closed service_session
--   table was considered and rejected as denormalization for a
--   read path the join already serves.
-- - No status indexes: GROUP BY status runs over the
--   window-bounded set, which the range indexes already delimit.
-- - No change to V1-V12 (project convention: migrations are immutable
--   once applied).

CREATE INDEX idx_appointment_analytics
    ON appointment (organization_id, start_at);

CREATE INDEX idx_queue_entry_analytics
    ON queue_entry (organization_id, joined_at);

CREATE INDEX idx_service_session_branch_started
    ON service_session (branch_id, started_at);
