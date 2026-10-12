-- V6__create_appointments.sql — Appointment booking foundation (Phase 2E).
--
-- Booked [start, end) service intervals per customer. Instants are stored as
-- TIMESTAMPTZ (unambiguous across zones); the branch time_zone (added here)
-- translates them to wall-clock for working-hour checks. See ADR-011.
--
-- Decisions recorded here:
-- - branch.time_zone is REQUIRED (IANA id string): booking cannot convert
--   instants to branch-local times without it, and a silent UTC default
--   would book wrong local times. Plain NOT NULL (no default that lies);
--   dev databases with pre-existing branch rows use the reset procedure.
-- - end_at is SNAPSHOTTED at booking (start + service duration): later
--   duration edits never rewrite history.
-- - no_service_double_booking excludes overlapping [start, end) per service
--   (capacity: one appointment per service per interval, documented
--   assumption) and no_customer_double_booking per customer — both PARTIAL
--   to SCHEDULED/CONFIRMED, so CANCELLED/COMPLETED/NO_SHOW rows never block
--   re-booking. Adjacent intervals ([10:00,10:30) + [10:30,11:00)) do not
--   overlap. service_id implies its branch, so no branch-wide serialization.
-- - Ownership composites (Phase 2B/2C pattern): (service_id, branch_id),
--   (customer_id, organization_id), (branch_id, organization_id) — a
--   wrong-branch service or foreign-organization customer is rejected by
--   the database on every path. Targets already exist (V3/V4 uniques).
-- - chk_appointment_range CHECK (end_at > start_at): empty/reversed
--   intervals impossible even via raw SQL.
-- - idx_appointment_branch_time (branch_id, start_at): the one read path no
--   exclusion covers ("appointments for a branch in a range"). Service and
--   customer lookups are served leftmost by their exclusion indexes.

ALTER TABLE branch
    ADD COLUMN time_zone VARCHAR(100) NOT NULL;

CREATE TABLE appointment (
    id              UUID          NOT NULL,
    organization_id UUID          NOT NULL,
    branch_id       UUID          NOT NULL,
    service_id      UUID          NOT NULL,
    customer_id     UUID          NOT NULL,
    start_at        TIMESTAMPTZ   NOT NULL,
    end_at          TIMESTAMPTZ   NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_appointment PRIMARY KEY (id),
    CONSTRAINT fk_appointment_organization FOREIGN KEY (organization_id)
        REFERENCES organization (id),
    CONSTRAINT fk_appointment_branch_organization
        FOREIGN KEY (branch_id, organization_id)
        REFERENCES branch (id, organization_id),
    CONSTRAINT fk_appointment_service_branch
        FOREIGN KEY (service_id, branch_id)
        REFERENCES service (id, branch_id),
    CONSTRAINT fk_appointment_customer_organization
        FOREIGN KEY (customer_id, organization_id)
        REFERENCES app_user (id, organization_id),
    CONSTRAINT chk_appointment_range CHECK (end_at > start_at),
    CONSTRAINT no_service_double_booking EXCLUDE USING gist (
        service_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    ) WHERE (status IN ('SCHEDULED', 'CONFIRMED')),
    CONSTRAINT no_customer_double_booking EXCLUDE USING gist (
        customer_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    ) WHERE (status IN ('SCHEDULED', 'CONFIRMED'))
);

CREATE INDEX idx_appointment_branch_time ON appointment (branch_id, start_at);
