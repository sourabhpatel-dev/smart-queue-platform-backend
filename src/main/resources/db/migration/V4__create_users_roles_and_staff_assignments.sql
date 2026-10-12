-- V4__create_users_roles_and_staff_assignments.sql — Users & staff (Phase 2C).
--
-- App-level accounts owned by an organization, platform-level role grants,
-- and branch staff assignments. No passwords or authentication fields —
-- those belong to the future security phase. See ADR-009.
--
-- Decisions recorded here:
-- - Table is app_user: USER is reserved in PostgreSQL.
-- - email is GLOBALLY unique: one email → one account, so a future login
--   lookup is unambiguous. Display names carry no uniqueness.
-- - No role entity: roles are a fixed platform vocabulary stored as strings
--   in user_role (PK covers uniqueness). No seed rows, no org-scoping puzzle.
-- - uq_user_id_org / uq_branch_id_organization exist solely as targets for
--   the staff-assignment composite foreign keys below (Phase 2B pattern).
-- - staff_assignment pins organization_id with COMPOSITE foreign keys
--   (user_id, organization_id) and (branch_id, organization_id): a user from
--   Organization A can never be assigned to a branch from Organization B —
--   rejected by the database on every code path.
-- - uq_staff_user_branch UNIQUE (user_id, branch_id): no duplicate rows.
--   Organization is NOT in the key — the branch already pins it.
-- - idx_user_organization (organization_id): serves "users in an
--   organization". idx_staff_branch (branch_id): serves "assignments for a
--   branch". user_role's PK (user_id, role) already serves "roles for a
--   user" via its leftmost column. No other secondary indexes.

CREATE TABLE app_user (
    id              UUID          NOT NULL,
    organization_id UUID          NOT NULL,
    email           VARCHAR(255)  NOT NULL,
    display_name    VARCHAR(150)  NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT fk_user_organization FOREIGN KEY (organization_id)
        REFERENCES organization (id),
    CONSTRAINT uq_user_email UNIQUE (email),
    CONSTRAINT uq_user_id_organization UNIQUE (id, organization_id)
);

CREATE INDEX idx_user_organization ON app_user (organization_id);

CREATE TABLE user_role (
    user_id UUID         NOT NULL,
    role    VARCHAR(20)  NOT NULL,
    CONSTRAINT pk_user_role PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id)
        REFERENCES app_user (id)
);

-- Composite target for the staff-assignment branch leg (V2 created branch
-- without it; new constraint via new migration — V2 itself is untouched).
ALTER TABLE branch
    ADD CONSTRAINT uq_branch_id_organization UNIQUE (id, organization_id);

CREATE TABLE staff_assignment (
    id              UUID          NOT NULL,
    organization_id UUID          NOT NULL,
    user_id         UUID          NOT NULL,
    branch_id       UUID          NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_staff_assignment PRIMARY KEY (id),
    CONSTRAINT fk_staff_organization FOREIGN KEY (organization_id)
        REFERENCES organization (id),
    CONSTRAINT fk_staff_user_branch_organization
        FOREIGN KEY (user_id, organization_id)
        REFERENCES app_user (id, organization_id),
    CONSTRAINT fk_staff_branch_organization
        FOREIGN KEY (branch_id, organization_id)
        REFERENCES branch (id, organization_id),
    CONSTRAINT uq_staff_user_branch UNIQUE (user_id, branch_id)
);

CREATE INDEX idx_staff_branch ON staff_assignment (branch_id);
