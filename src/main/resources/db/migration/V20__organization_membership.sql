-- V20__organization_membership.sql - Customer identity decoupling foundation (Phase 4R.2, ADR-040).
--
-- Creates the organization-membership table that becomes the authorization
-- fact for operational access (STAFF/MANAGER/ADMIN), backfills it from the
-- legacy per-user organization relationship, and removes the two database
-- constraints that structurally tie every customer to exactly one
-- organization. This is the persistence foundation ONLY: JWT, principal,
-- TenantContext, authentication, registration, and authorization behavior
-- are intentionally untouched in this phase (later 4R stages).
--
-- Decisions recorded here:
-- - organization_membership is a NEW concept, distinct from
--   staff_assignment: membership = "user holds role R at organization O";
--   assignment = "user staffs branch B". A user may later hold memberships
--   at several organizations; the legacy app_user.organization_id column is
--   retained (now nullable) as that user's primary organization so
--   staff_assignment's composite FK and the running application keep
--   working until 4R.3+ migrates them.
-- - uq_membership_user_organization UNIQUE (user_id, organization_id):
--   one membership row per user+organization. Not email (identity is the
--   user id), never frontend-controlled.
-- - Backfill: every existing user holding an operational role
--   (STAFF/MANAGER/ADMIN) with a legacy organization gets exactly one
--   membership row; customers get NONE (no silent organization assignment).
--   Multi-role users (possible under the old user_role model, PK per role)
--   collapse to their highest-privilege role ADMIN > MANAGER > STAFF - a
--   documented derivation, not an invention; the original grants remain in
--   user_role untouched. Membership status mirrors UserStatus (ACTIVE ->
--   ACTIVE, INACTIVE -> INACTIVE) so deactivated accounts never grant
--   access. created_at inherits the account's creation (derivable fact),
--   updated_at is this migration's run time.
-- - The backfill uses WHERE NOT EXISTS instead of ON CONFLICT DO NOTHING:
--   a genuine duplicate would violate uq_membership_user_organization and
--   fail the migration loudly rather than being swallowed. Flyway runs each
--   version once; the guard only protects a manual re-execution.
-- - fk_appointment_customer_organization (V6) and
--   fk_entry_customer_organization (V7) are replaced by plain
--   customer_id -> app_user(id) FKs. These composites pinned the customer's
--   organization to the row's organization, which is what made
--   cross-organization booking structurally impossible. The rows' own
--   organization_id / branch_id columns are untouched, so historical
--   transactions keep their organization context (appointments and queue
--   entries still say WHICH organization/branch they happened at).
-- - staff_assignment's (user_id, organization_id) composite FK is kept: it
--   now precisely enforces that an assignment's user has that organization
--   as their primary one - customers (organization_id NULL) can never be
--   assigned, and operational users keep their protection.
-- - app_user.organization_id drops NOT NULL. Existing customer rows keep
--   their populated value as harmless legacy data (the application still
--   reads it until 4R.3); NULL-ing existing customers is deliberately
--   deferred to 4R.3 alongside the authentication change, because the
--   running AuthService builds the principal from that column today and
--   NULL-ing it early would break live customer logins.
-- - No change to V1-V19 (project convention: migrations are immutable once
--   applied).

-- 1. The membership table.
CREATE TABLE organization_membership (
    id              UUID          NOT NULL,
    user_id         UUID          NOT NULL,
    organization_id UUID          NOT NULL,
    role            VARCHAR(20)   NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_organization_membership PRIMARY KEY (id),
    CONSTRAINT fk_membership_user FOREIGN KEY (user_id)
        REFERENCES app_user (id),
    CONSTRAINT fk_membership_organization FOREIGN KEY (organization_id)
        REFERENCES organization (id),
    CONSTRAINT uq_membership_user_organization UNIQUE (user_id, organization_id)
);

-- idx_membership_organization serves "members of an organization" (the
-- future org-scoped authorization read). No separate user index: the
-- unique's leftmost column already serves user-first lookups.
CREATE INDEX idx_membership_organization
    ON organization_membership (organization_id);

-- 2. Backfill operational users from the legacy user+role grants.
-- One row per (user, organization), highest-privilege role on collapse,
-- status mirrored from UserStatus. Customers produce no rows.
WITH operational AS (
    SELECT u.id AS user_id,
           u.organization_id AS organization_id,
           u.status AS user_status,
           u.created_at AS created_at,
           MAX(CASE r.role
                   WHEN 'ADMIN' THEN 3
                   WHEN 'MANAGER' THEN 2
                   WHEN 'STAFF' THEN 1
                   ELSE 0
               END) AS privilege
    FROM app_user u
    JOIN user_role r ON r.user_id = u.id
    WHERE u.organization_id IS NOT NULL
      AND r.role IN ('ADMIN', 'MANAGER', 'STAFF')
    GROUP BY u.id, u.organization_id, u.status, u.created_at
)
INSERT INTO organization_membership
    (id, user_id, organization_id, role, status, created_at, updated_at)
SELECT gen_random_uuid(),
       operational.user_id,
       operational.organization_id,
       CASE operational.privilege
           WHEN 3 THEN 'ADMIN'
           WHEN 2 THEN 'MANAGER'
           ELSE 'STAFF'
       END,
       CASE WHEN operational.user_status = 'ACTIVE' THEN 'ACTIVE' ELSE 'INACTIVE' END,
       operational.created_at,
       NOW()
FROM operational
WHERE NOT EXISTS (
    SELECT 1 FROM organization_membership m
    WHERE m.user_id = operational.user_id
      AND m.organization_id = operational.organization_id
);

-- 3. Un-pin the customer legs: plain customer FKs replace the
-- customer+organization composites (rows keep their own organization_id).
ALTER TABLE appointment
    DROP CONSTRAINT fk_appointment_customer_organization;
ALTER TABLE appointment
    ADD CONSTRAINT fk_appointment_customer FOREIGN KEY (customer_id)
        REFERENCES app_user (id);

ALTER TABLE queue_entry
    DROP CONSTRAINT fk_entry_customer_organization;
ALTER TABLE queue_entry
    ADD CONSTRAINT fk_entry_customer FOREIGN KEY (customer_id)
        REFERENCES app_user (id);

-- 4. The legacy organization column becomes optional. The schema now
-- supports platform-level customers (organization_id NULL, no membership
-- rows); existing customer rows keep their value until 4R.3.
ALTER TABLE app_user
    ALTER COLUMN organization_id DROP NOT NULL;
