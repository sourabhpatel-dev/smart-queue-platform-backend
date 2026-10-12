-- V7__create_queue_and_queue_entries.sql — Queue & walk-ins (Phase 2F).
--
-- One logical queue per branch+service, walk-in entries with daily token
-- numbers, strict-priority atomic claims. PostgreSQL is the whole engine:
-- no Redis, no messaging. See ADR-012.
--
-- Decisions recorded here:
-- - uq_queue_branch_service UNIQUE (branch_id, service_id): exactly one
--   logical queue per pair — multiple counters share it, never one queue
--   per counter. History lives on entries, not retired queue rows.
-- - Queue/service and queue/branch consistency via COMPOSITE foreign keys
--   (service_id, branch_id) and (branch_id, organization_id) — a service
--   from Branch B can never back Branch A's queue (Phase 2B/2C pattern).
-- - uq_active_entry UNIQUE (queue_id, customer_id) WHERE status IN
--   ('WAITING','CALLED','SERVING'): one active entry per customer/queue;
--   terminal rows never block re-joining. Partial index (not a table
--   constraint) — JPA maps no equivalent.
-- - uq_entry_token UNIQUE (queue_id, business_date, token_number): the
--   backstop behind the counter-row allocation below.
-- - queue_token_counter(queue_id, business_date, next_number): the
--   concurrency-safe daily sequence. Join locks the day's row
--   (SELECT ... FOR UPDATE), takes the number, increments — never
--   MAX()+1. First join of the day inserts the row; the insert-race loser
--   retries and finds it present.
-- - idx_entry_queue_waiting (queue_id) WHERE status = 'WAITING': serves
--   per-queue waiting reads; the claim query itself locks via its own
--   plan. No other secondary indexes: exclusion-free design needs no more.

CREATE TABLE queue (
    id              UUID          NOT NULL,
    organization_id UUID          NOT NULL,
    branch_id       UUID          NOT NULL,
    service_id      UUID          NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_queue PRIMARY KEY (id),
    CONSTRAINT fk_queue_organization FOREIGN KEY (organization_id)
        REFERENCES organization (id),
    CONSTRAINT fk_queue_branch_organization
        FOREIGN KEY (branch_id, organization_id)
        REFERENCES branch (id, organization_id),
    CONSTRAINT fk_queue_service_branch
        FOREIGN KEY (service_id, branch_id)
        REFERENCES service (id, branch_id),
    CONSTRAINT uq_queue_branch_service UNIQUE (branch_id, service_id),
    CONSTRAINT uq_queue_id_organization UNIQUE (id, organization_id)
);

CREATE TABLE queue_entry (
    id              UUID          NOT NULL,
    organization_id UUID          NOT NULL,
    queue_id        UUID          NOT NULL,
    customer_id     UUID          NOT NULL,
    token_number    INTEGER       NOT NULL,
    business_date   DATE          NOT NULL,
    priority        VARCHAR(20)   NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    joined_at       TIMESTAMPTZ   NOT NULL,
    called_at       TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_queue_entry PRIMARY KEY (id),
    CONSTRAINT fk_entry_organization FOREIGN KEY (organization_id)
        REFERENCES organization (id),
    CONSTRAINT fk_entry_queue_organization
        FOREIGN KEY (queue_id, organization_id)
        REFERENCES queue (id, organization_id),
    CONSTRAINT fk_entry_customer_organization
        FOREIGN KEY (customer_id, organization_id)
        REFERENCES app_user (id, organization_id)
);

CREATE UNIQUE INDEX uq_active_entry
    ON queue_entry (queue_id, customer_id)
    WHERE status IN ('WAITING', 'CALLED', 'SERVING');

CREATE UNIQUE INDEX uq_entry_token
    ON queue_entry (queue_id, business_date, token_number);

CREATE INDEX idx_entry_queue_waiting
    ON queue_entry (queue_id)
    WHERE status = 'WAITING';

CREATE TABLE queue_token_counter (
    id              UUID          NOT NULL,
    queue_id        UUID          NOT NULL,
    business_date   DATE          NOT NULL,
    next_number     INTEGER       NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_token_counter PRIMARY KEY (id),
    CONSTRAINT fk_token_counter_queue FOREIGN KEY (queue_id)
        REFERENCES queue (id),
    CONSTRAINT uq_token_counter_queue_date UNIQUE (queue_id, business_date)
);
