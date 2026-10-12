-- V3__create_service_and_counter.sql — Services & counters (Phase 2B).
--
-- Branch owns both: service → branch and counter → branch (FK enforced),
-- so tenant paths service → branch → organization (and likewise for
-- counters) always resolve. See ADR-008 for the full reasoning.
--
-- Decisions recorded here:
-- - service.name / counter.name are unique WITHIN a branch
--   (uq_service_branch_name / uq_counter_branch_name), reusable across
--   branches — the Phase 2A naming pattern.
-- - standard_duration_minutes is INTEGER (whole minutes): Hibernate maps
--   Java Duration to an opaque NUMERIC of nanoseconds and rejects INTERVAL
--   under validation, so the explicit minutes column is the readable,
--   sortable choice. The unit is in the name; Service.getStandardDuration()
--   is the single Duration conversion point.
-- - counter_capability carries the shared branch_id with COMPOSITE foreign
--   keys (service_id, branch_id) and (counter_id, branch_id). A Branch-1
--   service can never link to a Branch-2 counter — the database rejects it
--   regardless of code path. uq_service_id_branch / uq_counter_id_branch
--   exist solely as targets for those composite keys.
-- - uq_capability_service_counter UNIQUE (service_id, counter_id): no
--   duplicate links; its btree serves service-first lookups.
-- - idx_capability_counter (counter_id): serves counter-first lookups
--   ("services supported by a counter", a future queue-engine read).
--   No other secondary indexes: none justified yet.

CREATE TABLE service (
    id                UUID          NOT NULL,
    branch_id         UUID          NOT NULL,
    name              VARCHAR(150)  NOT NULL,
    description       VARCHAR(1000),
    standard_duration_minutes INTEGER NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_service PRIMARY KEY (id),
    CONSTRAINT fk_service_branch FOREIGN KEY (branch_id)
        REFERENCES branch (id),
    CONSTRAINT uq_service_branch_name UNIQUE (branch_id, name),
    CONSTRAINT uq_service_id_branch UNIQUE (id, branch_id)
);

CREATE TABLE service_counter (
    id                UUID          NOT NULL,
    branch_id         UUID          NOT NULL,
    name              VARCHAR(100)  NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_service_counter PRIMARY KEY (id),
    CONSTRAINT fk_counter_branch FOREIGN KEY (branch_id)
        REFERENCES branch (id),
    CONSTRAINT uq_counter_branch_name UNIQUE (branch_id, name),
    CONSTRAINT uq_counter_id_branch UNIQUE (id, branch_id)
);

CREATE TABLE counter_capability (
    id                UUID          NOT NULL,
    branch_id         UUID          NOT NULL,
    service_id        UUID          NOT NULL,
    counter_id        UUID          NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_counter_capability PRIMARY KEY (id),
    CONSTRAINT fk_capability_branch FOREIGN KEY (branch_id)
        REFERENCES branch (id),
    CONSTRAINT fk_capability_service FOREIGN KEY (service_id, branch_id)
        REFERENCES service (id, branch_id),
    CONSTRAINT fk_capability_counter FOREIGN KEY (counter_id, branch_id)
        REFERENCES service_counter (id, branch_id),
    CONSTRAINT uq_capability_service_counter UNIQUE (service_id, counter_id)
);

CREATE INDEX idx_capability_counter ON counter_capability (counter_id);
