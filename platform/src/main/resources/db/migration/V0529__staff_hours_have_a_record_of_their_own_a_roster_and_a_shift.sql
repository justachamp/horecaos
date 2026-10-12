-- ADR 0156: non-courier staff hours get a record of their own -- a rota and a timesheet --
-- in a new `workforce` module, built on ADR 0042's plan-and-fact vocabulary and variance
-- discipline and sharing none of its tables, its self-employment rules or its gate.
--
-- Two tables, and the reason there are two: a roster entry is the PLAN a manager authored
-- (a rota, not an offer: an employee is scheduled and does not accept), a shift is the FACT
-- (the only source of paid time). Paid hours come from the shift, never from the roster;
-- ADR 0042's "Pay from the roster" rejection is reaffirmed, not reopened.
--
-- A shift is a timesheet and never a permission: no endpoint, key or job refuses an order
-- action, a kitchen action or a sign-in because no shift is open, and the schema has no
-- column that could make it one.
--
-- Nothing is deleted (the grants at the foot give no DELETE): a mistaken shift is VOIDED
-- with a reason, a cancelled entry is CANCELLED, and the identity behind a row is
-- anonymised in place by iam's retention sweeper, so an old shift reads «S-0142».
--
-- Isolation is by composite keys (every foreign key carries tenant_id), a tenant predicate
-- in every statement and the cross-tenant negative tests; the schema is not in ADR 0056's
-- row-level-security set (inventory is the only schema in it today).
--
-- btree_gist exists since V0025.

CREATE SCHEMA IF NOT EXISTS workforce;
GRANT USAGE ON SCHEMA workforce TO horecaos_application;

CREATE TABLE workforce.staff_roster_entries (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    staff_member_id uuid NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    planned_start timestamptz NOT NULL,
    planned_end timestamptz NOT NULL,
    -- A planned break is deducted whether or not it was taken, and a manager corrects it by
    -- hand: staff do not tap for a break (ADR 0156, Alternatives: per-break clocking).
    planned_break_minutes smallint NOT NULL DEFAULT 0,

    -- Keycloak subjects, the string iam.grants.principal_subject already carries. A roster
    -- entry stores no name, phone, position or network address.
    created_by varchar(255) NOT NULL,
    published_at timestamptz,
    published_by varchar(255),
    cancel_reason_code varchar(32),

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_staff_roster_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_staff_roster_member FOREIGN KEY (staff_member_id, tenant_id)
        REFERENCES iam.staff_members (id, tenant_id),
    CONSTRAINT uq_staff_roster_identity UNIQUE (id, tenant_id),

    CONSTRAINT ck_staff_roster_status CHECK (status IN (
        'DRAFT', 'PUBLISHED', 'CONSUMED', 'MISSED', 'CANCELLED')),
    -- Sixteen hours is a data-sanity bound that catches a mistyped date. It is not a legal
    -- limit, and the platform enforces no working-time rule (ADR 0156, first open input).
    CONSTRAINT ck_staff_roster_window CHECK (
        planned_end > planned_start AND planned_end - planned_start <= interval '16 hours'),
    CONSTRAINT ck_staff_roster_break CHECK (planned_break_minutes BETWEEN 0 AND 240),
    CONSTRAINT ck_staff_roster_publication_pair CHECK ((published_at IS NULL) = (published_by IS NULL)),
    -- One-directional on purpose, as courier_roster_entries (V0262) is: a DRAFT never carries
    -- a publish stamp, but a CANCELLED entry that was published keeps its stamp.
    CONSTRAINT ck_staff_roster_draft_unpublished CHECK (status <> 'DRAFT' OR published_at IS NULL),
    CONSTRAINT ck_staff_roster_needs_publish CHECK (
        status NOT IN ('PUBLISHED', 'CONSUMED', 'MISSED') OR published_at IS NOT NULL),
    CONSTRAINT ck_staff_roster_cancel_reason CHECK (
        (status = 'CANCELLED') = (cancel_reason_code IS NOT NULL)),
    CONSTRAINT ck_staff_roster_version CHECK (version >= 1),

    -- A person cannot hold two live entries that overlap, at any branch.
    CONSTRAINT ex_staff_roster_no_overlap EXCLUDE USING gist (
        tenant_id WITH =,
        staff_member_id WITH =,
        tstzrange(planned_start, planned_end) WITH &&)
        WHERE (status IN ('DRAFT', 'PUBLISHED', 'CONSUMED', 'MISSED'))
);

COMMENT ON TABLE workforce.staff_roster_entries IS
    'ADR 0156. The plan: a rota a manager authored for non-courier staff (DRAFT -> PUBLISHED -> CONSUMED | MISSED | CANCELLED). Never a source of paid time and never a gate. Holds no name, phone or position.';

CREATE INDEX ix_staff_roster_branch_window
    ON workforce.staff_roster_entries (tenant_id, location_id, planned_start);
CREATE INDEX ix_staff_roster_member_window
    ON workforce.staff_roster_entries (tenant_id, staff_member_id, planned_start);
-- The sweeper that marks a published entry MISSED reads by end.
CREATE INDEX ix_staff_roster_published_end
    ON workforce.staff_roster_entries (planned_end) WHERE status = 'PUBLISHED';

CREATE TABLE workforce.staff_shifts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    staff_member_id uuid NOT NULL,
    roster_entry_id uuid,

    status varchar(20) NOT NULL DEFAULT 'OPEN',
    started_at timestamptz NOT NULL,
    ended_at timestamptz,

    -- Two console sources in v1, both at a branch, neither with a position. The terminal
    -- source is reserved by name and not built: a column value no writer produces is the
    -- configuration that silently does nothing, so the record that gives a shared device a
    -- human identity adds TERMINAL in its own migration.
    open_source varchar(16) NOT NULL,
    close_source varchar(16),

    break_seconds integer NOT NULL DEFAULT 0,
    worked_seconds bigint,
    paid_seconds bigint,
    -- Snapshots from the matched roster entry. NULL is not zero: no plan is no comparison.
    planned_seconds bigint,
    variance_seconds bigint,
    -- The ADR 0030 value resolved at close, so changing it in October does not re-judge September.
    variance_threshold_minutes smallint,
    business_date date NOT NULL,

    edit_reason_code varchar(32),
    -- The last manager who authored or changed the hours, so `workforce.shift.approver_must_differ`
    -- can compare the approver with the editor. NULL for a shift nobody but its owner touched.
    last_edited_by varchar(255),
    approved_by varchar(255),
    approved_at timestamptz,
    approval_reason_code varchar(32),
    voided_by varchar(255),
    voided_at timestamptz,
    void_reason_code varchar(32),

    version integer NOT NULL DEFAULT 1,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_staff_shift_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_staff_shift_member FOREIGN KEY (staff_member_id, tenant_id)
        REFERENCES iam.staff_members (id, tenant_id),
    CONSTRAINT fk_staff_shift_roster_entry FOREIGN KEY (roster_entry_id, tenant_id)
        REFERENCES workforce.staff_roster_entries (id, tenant_id),
    CONSTRAINT uq_staff_shift_identity UNIQUE (id, tenant_id),

    CONSTRAINT ck_staff_shift_status CHECK (status IN ('OPEN', 'AWAITING_APPROVAL', 'CLOSED', 'VOIDED')),
    CONSTRAINT ck_staff_shift_sources CHECK (
        open_source IN ('CONSOLE_SELF', 'CONSOLE_MANAGER')
        AND (close_source IS NULL OR close_source IN ('CONSOLE_SELF', 'CONSOLE_MANAGER'))),
    CONSTRAINT ck_staff_shift_close_pair CHECK ((ended_at IS NULL) = (close_source IS NULL)),
    CONSTRAINT ck_staff_shift_settled CHECK (
        status NOT IN ('CLOSED', 'AWAITING_APPROVAL')
        OR (ended_at IS NOT NULL AND worked_seconds IS NOT NULL AND paid_seconds IS NOT NULL)),
    CONSTRAINT ck_staff_shift_open_has_no_end CHECK (status <> 'OPEN' OR ended_at IS NULL),
    -- Sixteen hours: the data-sanity bound, not a legal limit.
    CONSTRAINT ck_staff_shift_window CHECK (
        ended_at IS NULL OR (ended_at > started_at AND ended_at - started_at <= interval '16 hours')),
    CONSTRAINT ck_staff_shift_break CHECK (
        break_seconds >= 0 AND (worked_seconds IS NULL OR break_seconds <= worked_seconds)),
    CONSTRAINT ck_staff_shift_void CHECK (
        status <> 'VOIDED' OR (void_reason_code IS NOT NULL AND voided_by IS NOT NULL AND voided_at IS NOT NULL)),
    CONSTRAINT ck_staff_shift_approval_pair CHECK (
        (approved_by IS NULL) = (approved_at IS NULL)
        AND (approved_by IS NULL) = (approval_reason_code IS NULL)),
    CONSTRAINT ck_staff_shift_version CHECK (version >= 1),

    -- One open shift per person.
    CONSTRAINT ex_staff_shift_no_overlap EXCLUDE USING gist (
        tenant_id WITH =,
        staff_member_id WITH =,
        tstzrange(started_at, coalesce(ended_at, 'infinity'::timestamptz)) WITH &&)
        WHERE (status <> 'VOIDED')
);

-- At most one open shift per person, by database constraint (ADR 0156 Decision 4).
CREATE UNIQUE INDEX ux_staff_shift_one_open
    ON workforce.staff_shifts (tenant_id, staff_member_id) WHERE status = 'OPEN';

COMMENT ON TABLE workforce.staff_shifts IS
    'ADR 0156. The fact: a timesheet row for non-courier staff and the only source of paid time (OPEN -> CLOSED, or OPEN -> AWAITING_APPROVAL -> CLOSED over the variance threshold; VOIDED with a reason, never deleted). A timesheet and never a permission: nothing refuses work for want of an open shift. Stores no name, phone, position, network address or device identifier.';
COMMENT ON COLUMN workforce.staff_shifts.variance_seconds IS
    'paid_seconds minus planned_seconds; NULL when no roster entry was matched -- null is not zero.';
COMMENT ON COLUMN workforce.staff_shifts.open_source IS
    'CONSOLE_SELF (the person clocked in; the time is the server''s) or CONSOLE_MANAGER (a manager recorded it). TERMINAL is reserved and added by the record that decides terminals (ADR 0156, Open inputs).';

CREATE INDEX ix_staff_shift_branch_start
    ON workforce.staff_shifts (tenant_id, location_id, started_at DESC);
CREATE INDEX ix_staff_shift_member_start
    ON workforce.staff_shifts (tenant_id, staff_member_id, started_at DESC);
CREATE INDEX ix_staff_shift_business_date
    ON workforce.staff_shifts (tenant_id, business_date);
-- The unresolved-shift refusal and the open-too-long flag read these.
CREATE INDEX ix_staff_shift_unresolved
    ON workforce.staff_shifts (tenant_id, started_at) WHERE status IN ('OPEN', 'AWAITING_APPROVAL');

-- Nothing is deleted, so no DELETE is granted (ADR 0156, Physical model).
GRANT SELECT, INSERT, UPDATE ON workforce.staff_roster_entries TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON workforce.staff_shifts TO horecaos_application;
