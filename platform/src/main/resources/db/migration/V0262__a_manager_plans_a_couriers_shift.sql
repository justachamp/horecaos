-- ADR 0042: the roster entry V0040 deliberately left uncreated.
--
-- V0040's own comment explains the omission: "A roster entry is an offer of
-- work whose only two consumers are the ENFORCED_WITH_ROSTER shift gate and
-- ADR 0036's capacity signal, and neither exists yet. A nullable foreign key
-- no writer ever populates is the 'configuration that silently does nothing'
-- V0030 refused three times; the column arrives with the code that reads it."
--
-- Neither consumer exists yet in this migration either. What changes is that
-- IA 3.5 (operations gap map row 3.5, wave T17) needs a planned shift a
-- manager can compare against what a courier actually opened, and today there
-- is nothing to compare against: only the shift the courier opened himself is
-- ever visible. So this table lands as a roster read/write surface on its own
-- -- the planned-versus-actual comparison is computed by joining this table
-- against fulfillment.courier_shifts at read time, by courier and by
-- overlapping time window, and not through a foreign key from one to the
-- other. That is a deliberate, narrower step than V0040 described:
-- `courier_shifts.roster_entry_id` stays uncreated, because nothing yet makes
-- opening a shift conditional on a published, accepted entry (that is the
-- ENFORCED_WITH_ROSTER gate, still not built -- CourierDispatchGate only
-- knows ENFORCED, ADVISORY and OFF) and a column no writer populates is
-- exactly the anti-pattern V0040 refused. A roster entry here is read
-- evidence, not yet an enforced precondition.
--
-- Lifecycle (ADR 0042's own sketch):
--   DRAFT -> PUBLISHED -> (ACCEPTED | DECLINED) -> (CONSUMED | MISSED | CANCELLED)
-- This wave's console can author DRAFT, publish it, and cancel a DRAFT or
-- PUBLISHED entry. ACCEPTED/DECLINED/CONSUMED/MISSED are reachable states in
-- the schema for the courier-facing half ADR 0042 describes and this wave
-- does not build; nothing here writes them yet, which is honest rather than
-- an oversight -- a status this table cannot yet reach is not decoration, it
-- is where the courier's own answer lands once that surface exists.

CREATE TABLE fulfillment.courier_roster_entries (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    courier_id uuid NOT NULL,
    engagement_id uuid NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    planned_start timestamptz NOT NULL,
    planned_end timestamptz NOT NULL,

    -- The manager who authored the entry. Never the courier -- authoring a
    -- roster is planning, and ADR 0042 keeps planning and attendance as
    -- separate facts on separate rows for exactly the reason the shift table's
    -- own ck_shift_open_source constraint keeps a shift courier-opened only.
    created_by uuid NOT NULL,
    published_at timestamptz,
    published_by uuid,
    -- Set once the courier answers PUBLISHED with ACCEPTED or DECLINED. No
    -- write path in this migration's code sets it; the column exists so the
    -- courier-facing answer, once built, has somewhere to land without a
    -- second migration.
    responded_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_roster_entry_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_roster_entry_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id),
    CONSTRAINT fk_roster_entry_engagement FOREIGN KEY (engagement_id, tenant_id)
        REFERENCES fulfillment.courier_engagements (id, tenant_id),
    CONSTRAINT uq_roster_entry_identity UNIQUE (id, tenant_id),

    CONSTRAINT ck_roster_entry_status CHECK (status IN (
        'DRAFT', 'PUBLISHED', 'ACCEPTED', 'DECLINED', 'CONSUMED', 'MISSED', 'CANCELLED')),
    CONSTRAINT ck_roster_entry_window CHECK (planned_end > planned_start),
    CONSTRAINT ck_roster_entry_publication_pair CHECK (
        (published_at IS NULL) = (published_by IS NULL)),
    -- One-directional on purpose: a DRAFT never carries a publish stamp, but a
    -- CANCELLED entry that was published before it was cancelled keeps its
    -- stamp rather than losing the history of how far it got.
    CONSTRAINT ck_roster_entry_draft_unpublished CHECK (
        status <> 'DRAFT' OR published_at IS NULL),
    CONSTRAINT ck_roster_entry_needs_publish CHECK (
        status NOT IN ('PUBLISHED', 'ACCEPTED', 'DECLINED', 'CONSUMED', 'MISSED')
        OR published_at IS NOT NULL),
    CONSTRAINT ck_roster_entry_needs_response CHECK (
        status NOT IN ('ACCEPTED', 'DECLINED') OR responded_at IS NOT NULL)
);

COMMENT ON TABLE fulfillment.courier_roster_entries IS
    'ADR 0042 roster entry: a planned shift a manager authored, so planned hours can be compared against what fulfillment.courier_shifts records a courier actually worked. Comparison is a read-time join by courier and overlapping window, not a foreign key -- see the file header.';

CREATE INDEX ix_roster_entry_location_window
    ON fulfillment.courier_roster_entries (tenant_id, brand_id, location_id, planned_start);

CREATE INDEX ix_roster_entry_courier_window
    ON fulfillment.courier_roster_entries (tenant_id, courier_id, planned_start);

GRANT SELECT, INSERT, UPDATE ON fulfillment.courier_roster_entries TO horecaos_application;
