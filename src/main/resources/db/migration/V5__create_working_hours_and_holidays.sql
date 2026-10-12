-- V5__create_working_hours_and_holidays.sql — Operating calendar (Phase 2D).
--
-- Weekly intervals plus explicit closure dates per branch. No appointment
-- logic — this migration only stores the calendar the future engine reads.
-- See ADR-010.
--
-- Decisions recorded here:
-- - One working_hour row = one continuous interval. Split days (lunch
--   breaks) are multiple rows; a weekday with no rows means normally
--   closed. No isClosed flag, no single open/close pair on branch.
-- - chk_working_hour_range CHECK (start_time < end_time): equals and
--   reversed ranges are impossible even via raw SQL (constructor guard
--   fails fast first).
-- - no_working_hour_overlap is an EXCLUSION constraint (needs btree_gist
--   for uuid/varchar equality): same branch + day + overlapping minute
--   ranges are rejected by the database on every path. int4range is
--   [start, end), so adjacent intervals (13:00 end, 13:00 start) are
--   correctly allowed. Times are minute-granular by constructor guard, so
--   the minute expressions are exact (sub-minute values would truncate
--   into empty ranges that never conflict).
-- - No UNIQUE(branch_id, day_of_week): it would forbid split days. The
--   exclusion already rejects every same-start pair (equal starts
--   necessarily overlap), so a start-uniqueness constraint would add
--   nothing. idx_working_hour_branch_day serves the future
--   "hours for branch + weekday" read as a plain btree.
-- - uq_holiday_branch_date UNIQUE (branch_id, holiday_date): one row per
--   branch/date; its btree serves branch/date lookups leftmost. Same date
--   across branches stays allowed. Full-day closures only.
-- - No organization_id on either table: branch FKs already fix the tenant
--   boundary (WorkingHour/Holiday → Branch → Organization). A redundant
--   org column with composite FKs would add nothing here — unlike 2B/2C,
--   there is no second same-level entity to cross-check against.

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE working_hour (
    id              UUID          NOT NULL,
    branch_id       UUID          NOT NULL,
    day_of_week     VARCHAR(10)   NOT NULL,
    start_time      TIME          NOT NULL,
    end_time        TIME          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_working_hour PRIMARY KEY (id),
    CONSTRAINT fk_working_hour_branch FOREIGN KEY (branch_id)
        REFERENCES branch (id),
    CONSTRAINT chk_working_hour_range CHECK (start_time < end_time),
    CONSTRAINT no_working_hour_overlap EXCLUDE USING gist (
        branch_id WITH =,
        day_of_week WITH =,
        int4range(
            ((EXTRACT(HOUR FROM start_time) * 60
                + EXTRACT(MINUTE FROM start_time))::INT),
            ((EXTRACT(HOUR FROM end_time) * 60
                + EXTRACT(MINUTE FROM end_time))::INT)
        ) WITH &&
    )
);

CREATE INDEX idx_working_hour_branch_day ON working_hour (branch_id, day_of_week);

CREATE TABLE holiday (
    id              UUID          NOT NULL,
    branch_id       UUID          NOT NULL,
    holiday_date    DATE          NOT NULL,
    name            VARCHAR(150)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_holiday PRIMARY KEY (id),
    CONSTRAINT fk_holiday_branch FOREIGN KEY (branch_id)
        REFERENCES branch (id),
    CONSTRAINT uq_holiday_branch_date UNIQUE (branch_id, holiday_date)
);
