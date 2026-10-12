-- V21__organization_onboarding.sql - Organization onboarding & approval (Phase 4R.4, ADR-041).
--
-- Business applications are created PENDING_APPROVAL and decided by platform
-- review (ACTIVE or REJECTED with an owner-visible reason). This migration
-- adds only the review-audit columns the new lifecycle needs:
--
-- - rejection_reason: owner-visible text set on REJECTED, cleared on
--   approval. There is deliberately NO internal-notes column: anything
--   stored here is returned to the applicant, so reviewers cannot
--   accidentally persist internal commentary in an owner-visible field
--   (and cannot stash internal notes anywhere else on this row).
-- - decided_at: when platform review decided (approval or rejection).
--   Null while pending and for all pre-V21 rows.
-- - decided_by_user_id: the reviewing platform administrator (audit
--   only), FK to app_user(id) with no cascade. Plain UUID rather than a
--   JPA association on the entity so the reviewer never becomes a tenant
--   leg of the row. Null while pending and for all pre-V21 rows.
--
-- No status CHECK constraint: organization.status stays an open VARCHAR
-- (V2 convention) with the vocabulary enforced in code
-- (OrganizationStatus + guarded transitions). PENDING_APPROVAL/REJECTED
-- therefore need no DDL beyond these columns.
--
-- idx_organization_status serves the platform review listing ("pending
-- applications oldest first"). No partial index: all four statuses are
-- queried (pending for review, active for audits), and one small btree
-- covers both.
--
-- Legacy data: every pre-V21 row keeps its ACTIVE/INACTIVE status with
-- NULL review columns — existing organizations stay discoverable and
-- bookable exactly as before. No backfill, no default, no NOT NULL:
-- a wrong default (e.g. mass-PENDING) would disable live businesses.
--
-- No change to V1-V20 (project convention: migrations are immutable once
-- applied).

ALTER TABLE organization
    ADD COLUMN rejection_reason VARCHAR(500),
    ADD COLUMN decided_at TIMESTAMPTZ,
    ADD COLUMN decided_by_user_id UUID,
    ADD CONSTRAINT fk_organization_decided_by FOREIGN KEY (decided_by_user_id)
        REFERENCES app_user (id);

CREATE INDEX idx_organization_status
    ON organization (status);
