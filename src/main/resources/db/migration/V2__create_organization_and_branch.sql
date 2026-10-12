-- V2__create_organization_and_branch.sql — Organization → Branch foundation (Phase 2A).
--
-- Creates the top of the tenancy hierarchy. Every branch belongs to exactly
-- one organization (FK enforced). See ADR-006 (identity/timestamps) and
-- ADR-007 (ownership, naming constraints).
--
-- Naming decisions recorded here:
-- - organization.name is NOT unique: display names may repeat across tenants;
--   the UUID primary key is the unique business identifier.
-- - branch names are unique WITHIN an organization
--   (uq_branch_organization_name): names are org-scoped identifiers and a
--   duplicate inside one org signals a data error, while different orgs may
--   reuse names (tenant isolation).
-- - No separate index on branch.organization_id: the composite unique's
--   btree on (organization_id, name) already serves org-scoped lookups via
--   its leftmost column. No duplicated indexes.

CREATE TABLE organization (
    id              UUID         NOT NULL,
    name            VARCHAR(150) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_organization PRIMARY KEY (id)
);

CREATE TABLE branch (
    id              UUID         NOT NULL,
    organization_id UUID         NOT NULL,
    name            VARCHAR(150) NOT NULL,
    address_line1   VARCHAR(255),
    address_line2   VARCHAR(255),
    city            VARCHAR(100) NOT NULL,
    state           VARCHAR(100),
    postal_code     VARCHAR(20),
    country         VARCHAR(100) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_branch PRIMARY KEY (id),
    CONSTRAINT fk_branch_organization FOREIGN KEY (organization_id)
        REFERENCES organization (id),
    CONSTRAINT uq_branch_organization_name UNIQUE (organization_id, name)
);
