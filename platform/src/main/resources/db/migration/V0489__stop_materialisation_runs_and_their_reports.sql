-- ADR 0141 (Accepted 2026-10-01), "Rollback: freeze, do not disable", switch three: the
-- decommission (inventory.stops.read_enabled) and the materialisation run that must precede it.
--
-- After stops exist, a stop row is for many dishes the ONLY record that they are stopped: an
-- UNTRACKED or QUANTITY item has no position boolean; a BRAND, MENU or CHANNEL stop has no
-- position at all. Ceasing to read stops therefore sells every one of those dishes again in the
-- same instant, and the platform refuses to do it blind. The materialisation run writes each
-- stop in force onto positions wherever that is exact (binary_available = false on a BINARY
-- item), and REPORTS everything it could not carry: that report is what an owner acknowledges
-- ("these dishes will be on sale again"), and only an acknowledged report lets the switch turn.
--
-- Three tables, all tenant-scoped and all under row-level security like the table they describe
-- (ADR 0056):
--
--   stop_materialisation_runs   one run of one brand: when it read the stops, what it did, and
--                               who acknowledged its report.
--   stop_materialisation_stops  the exact set of stops a run SAW and carried. This is the guard's
--                               evidence, and it is a set of ids rather than a timestamp
--                               comparison on purpose: "an embargo created after the run
--                               re-blocks it" must not depend on two clocks and a transaction
--                               that committed a moment after the run's read. A stop that is in
--                               force and not in an acknowledged run's set is a stop nobody has
--                               been told about.
--   stop_materialisation_lines  what the run could NOT carry, one line per stop and location:
--                               the report. Identifiers and stable codes only -- no dish name, no
--                               free text (ADR 0029); the console resolves names through the
--                               authorized catalog API.
--
-- Rows are never deleted: a run and its report are the evidence that a decommission was
-- understood before it was done.

CREATE TABLE inventory.stop_materialisation_runs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    status varchar(16) NOT NULL,
    started_by varchar(128) NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,

    -- What the run did, for the report's header. positions_written counts positions set to
    -- unavailable; positions_already_unavailable counts positions that already were (the stop
    -- adds nothing there, and nothing is lost by ignoring it); not_carried counts report lines;
    -- failed_stops counts stops whose write failed and which are therefore NOT in the run's set.
    stops_seen integer NOT NULL DEFAULT 0,
    positions_written integer NOT NULL DEFAULT 0,
    positions_already_unavailable integer NOT NULL DEFAULT 0,
    not_carried integer NOT NULL DEFAULT 0,
    failed_stops integer NOT NULL DEFAULT 0,

    -- The acknowledgement of the report, by a holder of inventory.stop.manage at BRAND scope.
    acknowledged_by varchar(128),
    acknowledged_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_materialisation_run_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_materialisation_run_completed CHECK (
        (status = 'RUNNING' AND completed_at IS NULL) OR (status <> 'RUNNING' AND completed_at IS NOT NULL)),
    -- Only a run that finished can be acknowledged, and the acknowledgement names who and when.
    CONSTRAINT ck_materialisation_run_acknowledged CHECK (
        (acknowledged_at IS NULL AND acknowledged_by IS NULL)
        OR (status = 'COMPLETED' AND acknowledged_at IS NOT NULL AND acknowledged_by IS NOT NULL)),
    CONSTRAINT ck_materialisation_run_counts CHECK (
        stops_seen >= 0 AND positions_written >= 0 AND positions_already_unavailable >= 0
        AND not_carried >= 0 AND failed_stops >= 0),
    CONSTRAINT ck_materialisation_run_version CHECK (version >= 1),
    CONSTRAINT fk_materialisation_run_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id)
);

-- One run of a brand at a time: two overlapping runs would each believe they carried the stops
-- the other was writing.
CREATE UNIQUE INDEX ux_materialisation_run_running
    ON inventory.stop_materialisation_runs (tenant_id, brand_id)
    WHERE status = 'RUNNING';

CREATE INDEX ix_materialisation_run_brand
    ON inventory.stop_materialisation_runs (tenant_id, brand_id, started_at DESC);

CREATE TABLE inventory.stop_materialisation_stops (
    run_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    stop_id uuid NOT NULL,

    positions_written integer NOT NULL DEFAULT 0,
    positions_already_unavailable integer NOT NULL DEFAULT 0,
    not_carried integer NOT NULL DEFAULT 0,

    PRIMARY KEY (run_id, stop_id),
    CONSTRAINT ck_materialisation_stop_counts CHECK (
        positions_written >= 0 AND positions_already_unavailable >= 0 AND not_carried >= 0),
    CONSTRAINT fk_materialisation_stop_run FOREIGN KEY (run_id)
        REFERENCES inventory.stop_materialisation_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_materialisation_stop_stop FOREIGN KEY (stop_id)
        REFERENCES inventory.availability_stops (id)
);

CREATE INDEX ix_materialisation_stop_stop ON inventory.stop_materialisation_stops (stop_id);

CREATE TABLE inventory.stop_materialisation_lines (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    stop_id uuid NOT NULL,

    -- Copied from the stop so the report reads as it did when it was acknowledged, whatever the
    -- stop became afterwards.
    variant_id uuid NOT NULL,
    scope_type varchar(16) NOT NULL,
    source varchar(16) NOT NULL,
    location_id uuid,
    channel_id uuid,
    menu_id uuid,

    -- Why this stop did not land on a position there:
    --   UNTRACKED_ITEM, QUANTITY_ITEM     no position boolean to set; the dish sells again
    --   CHANNEL_SCOPE                     a position is location-wide, so writing it would stop
    --                                     the dish on channels the stop never covered
    --   MENU_NOT_EVERY_CHANNEL            the menu is published to some channels here and not
    --                                     others, so the same over-stop applies
    --   WRITE_FAILED                      the write itself failed; the stop is NOT in the run's
    --                                     set and the switch stays blocked until a run carries it
    reason_code varchar(32) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_materialisation_line_reason CHECK (reason_code IN (
        'UNTRACKED_ITEM', 'QUANTITY_ITEM', 'CHANNEL_SCOPE', 'MENU_NOT_EVERY_CHANNEL', 'WRITE_FAILED')),
    CONSTRAINT fk_materialisation_line_run FOREIGN KEY (run_id)
        REFERENCES inventory.stop_materialisation_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_materialisation_line_stop FOREIGN KEY (stop_id)
        REFERENCES inventory.availability_stops (id)
);

-- The report's page: a run's lines in a stable order.
CREATE INDEX ix_materialisation_line_run ON inventory.stop_materialisation_lines (run_id, id);

COMMENT ON TABLE inventory.stop_materialisation_runs IS
    'ADR 0141, rollback switch three. One materialisation run of one brand: it wrote each stop in force onto positions wherever that is exact and reported what it could not carry. Only a COMPLETED, acknowledged run lets inventory.stops.read_enabled be turned off.';
COMMENT ON TABLE inventory.stop_materialisation_stops IS
    'The exact set of stops a run saw and carried. The guard on inventory.stops.read_enabled refuses while a stop is in force that no acknowledged run lists here.';
COMMENT ON TABLE inventory.stop_materialisation_lines IS
    'The report of a materialisation run: what it could not carry, by stop and location. Identifiers and stable codes only.';

GRANT SELECT, INSERT, UPDATE, DELETE ON inventory.stop_materialisation_runs TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON inventory.stop_materialisation_stops TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON inventory.stop_materialisation_lines TO horecaos_application;

-- ADR 0056: inventory enforces row-level security, so its next tables do too.
SELECT platform.enable_tenant_row_level_security('inventory.stop_materialisation_runs');
SELECT platform.enable_tenant_row_level_security('inventory.stop_materialisation_stops');
SELECT platform.enable_tenant_row_level_security('inventory.stop_materialisation_lines');
