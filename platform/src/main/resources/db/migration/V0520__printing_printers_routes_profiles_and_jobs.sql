-- ADR 0154 (operations batch 20, wave w1): printing and receipts.
--
-- A new module, `printing`, that turns facts other modules own into a single 1-bit bitmap per
-- document and delivers it, at least once, to a printer a branch's own agent can reach. The module
-- renders and delivers and decides nothing commercial: it holds no money, no tax and no fiscal
-- identifier of its own, and every figure on paper is read from the owning module's api port when a
-- document is built.
--
--   printers             a physical (or browser) printer of one location: its roll width, and for an
--                        agent printer the agent device and the alias that agent advertises for it
--   print_routes         (location, document type, optional station) -> printer, with copies and an
--                        auto_print flag. A location with no routes prints nothing.
--   receipt_profiles     the closed set of presentation a brand may change: the logo flag and a footer
--                        of at most three lines per locale. Nothing else is a field -- the seller, the
--                        order number, the totals, the QR and the fiscal block cannot be moved, resized
--                        or removed by a tenant, because a presentation layer that can reorder evidence
--                        is a compliance defect (ADR 0154, Context).
--   print_jobs           a durable, leased, at-least-once row. The document is stored envelope-encrypted
--                        (ADR 0029) because a line's free-text note can hold anything a customer typed,
--                        and is erased on a schedule; the row, its digest and its outcome stay.
--   agent_printer_adverts  the printers each agent says it can reach, by the alias the agent chose
--                        locally (ADR 0154, Decision 9). A fifth table the record's Model does not list:
--                        its own endpoint, GET .../print/agents, lists "the printers each advertises",
--                        and an alias no printer row is bound to yet has nowhere else to live.
--
-- Tenant scope. Every row carries a non-null tenant_id, every unique key and every foreign key names
-- it, and every foreign key that reaches a row bound to a location carries location_id too, so the
-- database and not only the endpoint refuses a route naming a printer at another location, a printer
-- bound to an agent enrolled at another location, a job whose printer, route or claiming agent is at
-- another location, and a reprint of another tenant's job. Row-level security is on from this first
-- migration (ADR 0056): a new schema has no cross-tenant reader to retrofit, and the reconciling
-- sweeper and the purge job bind platform scope explicitly.
--
-- The shared device key. ADR 0154 names the location-carrying key on iam.device_principals
-- `uq_device_principal_location (tenant_id, location_id, id)` and says the first of the three device
-- migrations to merge adds it. V0501 (ADR 0151) already carries the same three columns as
-- `uq_device_principal_tenant_id_location (tenant_id, id, location_id)`. A foreign key matches a unique
-- constraint by its column set, so the references below use that one and add no second, identically
-- shaped constraint for the other two device waves to collide with.

CREATE SCHEMA IF NOT EXISTS printing;
GRANT USAGE ON SCHEMA printing TO horecaos_application;

-- ---------------------------------------------------------------------------
-- printers
-- ---------------------------------------------------------------------------

CREATE TABLE printing.printers (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    display_name varchar(120) NOT NULL,
    -- The roll, not a layout: 58 mm is 384 dots (32 cells of 12), 80 mm is 576 dots (48 cells).
    tape_width_mm smallint NOT NULL,
    -- The agent that serves this printer and the alias it advertises for it. Both null = BROWSER:
    -- an operator prints the preview bitmap through the browser at a workstation, and the platform
    -- can only record that it was handed off.
    agent_device_id uuid,
    agent_printer_key varchar(64),
    connection_kind varchar(16) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    -- What the agent last reported for this printer (DLE EOT status). Null until it has reported.
    last_status varchar(16),
    last_status_at timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_printer_tape_width CHECK (tape_width_mm IN (58, 80)),
    CONSTRAINT ck_printer_connection_kind CHECK (connection_kind IN ('AGENT', 'BROWSER')),
    CONSTRAINT ck_printer_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_printer_last_status CHECK (
        last_status IS NULL
        OR last_status IN ('ONLINE', 'OFFLINE', 'OUT_OF_PAPER', 'COVER_OPEN', 'ERROR', 'UNKNOWN')),
    CONSTRAINT ck_printer_last_status_pair CHECK ((last_status IS NULL) = (last_status_at IS NULL)),
    -- A database fact and not a service convention: an AGENT printer names both the agent and the
    -- alias, and a BROWSER printer names neither.
    CONSTRAINT ck_printer_connection_pair CHECK (
        (connection_kind = 'AGENT' AND agent_device_id IS NOT NULL AND agent_printer_key IS NOT NULL)
        OR (connection_kind = 'BROWSER' AND agent_device_id IS NULL AND agent_printer_key IS NULL)),
    CONSTRAINT ck_printer_name CHECK (btrim(display_name) <> ''),
    CONSTRAINT ck_printer_version CHECK (version >= 1),
    CONSTRAINT fk_printer_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    -- An agent serves printers at its own location only.
    CONSTRAINT fk_printer_agent_device FOREIGN KEY (tenant_id, agent_device_id, location_id)
        REFERENCES iam.device_principals (tenant_id, id, location_id),
    CONSTRAINT uq_printer_name UNIQUE (tenant_id, location_id, display_name),
    CONSTRAINT uq_printer_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_printer_location UNIQUE (tenant_id, location_id, id)
);

-- One live printer row per advertised alias: two rows for the same physical device would each be
-- claimed from and the same paper would come out twice.
CREATE UNIQUE INDEX uq_printer_agent_alias
    ON printing.printers (tenant_id, location_id, agent_device_id, agent_printer_key)
    WHERE status = 'ACTIVE' AND connection_kind = 'AGENT';

CREATE INDEX ix_printers_location ON printing.printers (tenant_id, location_id, status);

COMMENT ON TABLE printing.printers IS
    'ADR 0154. A printer of one location. AGENT printers are reached by an outbound-HTTPS print agent through the alias it advertises; BROWSER printers are printed through the operator''s browser. A tenant never types an address: the platform cannot make a branch machine dial one.';

-- ---------------------------------------------------------------------------
-- print_routes
-- ---------------------------------------------------------------------------

CREATE TABLE printing.print_routes (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    document_type varchar(20) NOT NULL,
    -- KITCHEN_TICKET only; null on a ticket route is the fallback for a station that has none of its own.
    station_id uuid,
    printer_id uuid NOT NULL,
    copies smallint NOT NULL DEFAULT 1,
    -- Default off: nothing prints by default (ADR 0154, Decision 6). The trigger is fixed by the
    -- document type and is deliberately not a column.
    auto_print boolean NOT NULL DEFAULT false,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_print_route_type CHECK (document_type IN ('KITCHEN_TICKET', 'ORDER_SLIP', 'FISCAL_RECEIPT')),
    CONSTRAINT ck_print_route_station CHECK (station_id IS NULL OR document_type = 'KITCHEN_TICKET'),
    CONSTRAINT ck_print_route_copies CHECK (copies BETWEEN 1 AND 3),
    CONSTRAINT ck_print_route_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_print_route_version CHECK (version >= 1),
    CONSTRAINT fk_print_route_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    -- A station of another location is refused here, by the composite key kitchen.stations carries
    -- for exactly this (uq_station_identity).
    CONSTRAINT fk_print_route_station FOREIGN KEY (station_id, tenant_id, location_id)
        REFERENCES kitchen.stations (id, tenant_id, location_id),
    -- A route names a printer at its own location only.
    CONSTRAINT fk_print_route_printer FOREIGN KEY (tenant_id, location_id, printer_id)
        REFERENCES printing.printers (tenant_id, location_id, id),
    CONSTRAINT uq_print_route_location UNIQUE (tenant_id, location_id, id)
);

-- One route per (document type, station, printer): the same ticket route twice would print twice.
CREATE UNIQUE INDEX uq_print_route_target ON printing.print_routes (
    tenant_id, location_id, document_type,
    coalesce(station_id, '00000000-0000-0000-0000-000000000000'::uuid), printer_id);

CREATE INDEX ix_print_routes_location ON printing.print_routes (tenant_id, location_id, document_type, status);

COMMENT ON TABLE printing.print_routes IS
    'ADR 0154. What prints where: (location, document type, optional station) -> printer, with copies and auto_print. A new location has none, and a document with no route prints nowhere.';

-- ---------------------------------------------------------------------------
-- receipt_profiles
-- ---------------------------------------------------------------------------

-- The footer is a closed shape: {locale: [up to three lines]}, a line at most a full 80 mm roll wide
-- (48 cells). Checked in the database because the profile is the one place a tenant authors text that
-- goes on a fiscal receipt, and a shape the application forgot to check would reach paper.
CREATE FUNCTION printing.footer_texts_valid(footer jsonb) RETURNS boolean
    LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_typeof(footer) = 'object' AND NOT EXISTS (
        SELECT 1
          FROM jsonb_each(footer) AS entry(locale, lines)
         WHERE jsonb_typeof(entry.lines) <> 'array'
            OR (CASE WHEN jsonb_typeof(entry.lines) = 'array' THEN jsonb_array_length(entry.lines) > 3 ELSE false END)
            OR EXISTS (
                SELECT 1
                  FROM jsonb_array_elements(
                           CASE WHEN jsonb_typeof(entry.lines) = 'array' THEN entry.lines ELSE '[]'::jsonb END)
                       AS line(value)
                 WHERE jsonb_typeof(line.value) <> 'string' OR char_length(line.value #>> '{}') > 48))
$$;

CREATE TABLE printing.receipt_profiles (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    print_logo boolean NOT NULL DEFAULT false,
    footer_texts jsonb NOT NULL DEFAULT '{}'::jsonb,
    version integer NOT NULL DEFAULT 1,
    updated_by varchar(255) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_receipt_profile PRIMARY KEY (tenant_id, brand_id),
    CONSTRAINT ck_receipt_profile_footer CHECK (printing.footer_texts_valid(footer_texts)),
    CONSTRAINT ck_receipt_profile_version CHECK (version >= 1),
    CONSTRAINT fk_receipt_profile_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id)
);

COMMENT ON TABLE printing.receipt_profiles IS
    'ADR 0154. The whole of what a brand may change about its paper: whether the logo prints and up to three footer lines per locale. The seller, the order number, the totals, the QR and the fiscal block are not fields. No template designer.';

-- ---------------------------------------------------------------------------
-- print_jobs
-- ---------------------------------------------------------------------------

CREATE TABLE printing.print_jobs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    printer_id uuid NOT NULL,
    -- Null for a manual job.
    route_id uuid,
    document_type varchar(20) NOT NULL,
    source_type varchar(20) NOT NULL,
    -- Names a row in another module's table and has no foreign key, by design: the unique key below
    -- and the sweeper guard it. The ticket (or the order, or the fiscal document) is the owning
    -- module's; this module never reads its table.
    source_id uuid,
    -- A kitchen ticket's release is revision 0 and each amendment raises it; an order slip carries the
    -- order's version; zero otherwise.
    source_revision integer NOT NULL DEFAULT 0,
    -- The station a KITCHEN_TICKET job prints. Not in the record's Model: two station routes that name
    -- one printer would otherwise collide on the idempotency key below and the second station's
    -- ticket would never exist.
    station_id uuid,
    copy_no smallint NOT NULL DEFAULT 1,
    origin varchar(8) NOT NULL,
    requested_by varchar(255),
    reason_code varchar(24),
    reprint_of uuid,
    status varchar(12) NOT NULL DEFAULT 'QUEUED',
    attempt integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    lease_token uuid,
    lease_expires_at timestamptz,
    claimed_by_device_id uuid,
    failure_code varchar(24),
    -- What the paper was resolved to when the job was made, so a reprint explains itself.
    locale varchar(8) NOT NULL,
    tape_width_mm smallint NOT NULL,
    profile_version integer,
    render_protocol varchar(24) NOT NULL DEFAULT 'ESCPOS_RASTER_1',
    -- A ProtectedValue envelope (ADR 0029); null once purged.
    protected_document text,
    -- SHA-256 of the canonical document, kept after the purge. The retry, delayed and copy lines are
    -- render inputs and are not part of it.
    document_digest varchar(64) NOT NULL,
    payload_purge_after timestamptz NOT NULL,
    queued_at timestamptz NOT NULL,
    printed_at timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_print_job_type CHECK (document_type IN ('KITCHEN_TICKET', 'ORDER_SLIP', 'FISCAL_RECEIPT', 'TEST_PAGE')),
    CONSTRAINT ck_print_job_source_type CHECK (source_type IN ('KITCHEN_TICKET', 'ORDER', 'FISCAL_DOCUMENT', 'NONE')),
    -- A database fact: a fiscal receipt is made from a fiscal document and from nothing else, and the
    -- other types each name theirs.
    CONSTRAINT ck_print_job_source_pair CHECK (
        (document_type = 'FISCAL_RECEIPT' AND source_type = 'FISCAL_DOCUMENT' AND source_id IS NOT NULL)
        OR (document_type = 'KITCHEN_TICKET' AND source_type = 'KITCHEN_TICKET' AND source_id IS NOT NULL)
        OR (document_type = 'ORDER_SLIP' AND source_type = 'ORDER' AND source_id IS NOT NULL)
        OR (document_type = 'TEST_PAGE' AND source_type = 'NONE' AND source_id IS NULL)),
    CONSTRAINT ck_print_job_station CHECK (station_id IS NULL OR document_type = 'KITCHEN_TICKET'),
    CONSTRAINT ck_print_job_copy CHECK (copy_no BETWEEN 1 AND 3),
    CONSTRAINT ck_print_job_origin CHECK (origin IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_print_job_origin_actor CHECK (
        (origin = 'MANUAL' AND requested_by IS NOT NULL AND reason_code IS NOT NULL)
        OR (origin = 'AUTO' AND requested_by IS NULL AND reprint_of IS NULL)),
    CONSTRAINT ck_print_job_status CHECK (status IN (
        'QUEUED', 'CLAIMED', 'PRINTED', 'HANDED_OFF', 'DEAD', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_print_job_attempt CHECK (attempt BETWEEN 0 AND 6),
    CONSTRAINT ck_print_job_lease_pair CHECK ((lease_token IS NULL) = (lease_expires_at IS NULL)),
    CONSTRAINT ck_print_job_claimed CHECK (status <> 'CLAIMED' OR (lease_token IS NOT NULL AND claimed_by_device_id IS NOT NULL)),
    CONSTRAINT ck_print_job_failure CHECK (
        failure_code IS NULL
        OR failure_code IN ('PRINTER_OFFLINE', 'OUT_OF_PAPER', 'COVER_OPEN', 'TRANSPORT', 'RENDER', 'LEASE_LAPSED')),
    CONSTRAINT ck_print_job_printed CHECK ((status = 'PRINTED') = (printed_at IS NOT NULL) OR status IN ('HANDED_OFF')),
    CONSTRAINT ck_print_job_tape CHECK (tape_width_mm IN (58, 80)),
    CONSTRAINT ck_print_job_digest CHECK (document_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_print_job_version CHECK (version >= 1),
    CONSTRAINT fk_print_job_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_print_job_printer FOREIGN KEY (tenant_id, location_id, printer_id)
        REFERENCES printing.printers (tenant_id, location_id, id),
    CONSTRAINT fk_print_job_route FOREIGN KEY (tenant_id, location_id, route_id)
        REFERENCES printing.print_routes (tenant_id, location_id, id),
    CONSTRAINT fk_print_job_station FOREIGN KEY (station_id, tenant_id, location_id)
        REFERENCES kitchen.stations (id, tenant_id, location_id),
    -- A reprint of another tenant's job is refused by the database.
    CONSTRAINT fk_print_job_reprint_of FOREIGN KEY (tenant_id, reprint_of)
        REFERENCES printing.print_jobs (tenant_id, id),
    -- A job's claiming agent is enrolled at the job's own location.
    CONSTRAINT fk_print_job_claimed_by FOREIGN KEY (tenant_id, claimed_by_device_id, location_id)
        REFERENCES iam.device_principals (tenant_id, id, location_id),
    CONSTRAINT uq_print_job_tenant_id UNIQUE (tenant_id, id)
);

-- Idempotent creation: the same OrderConfirmed delivered twice, a sweeper after a lost listener and
-- an amendment replayed each insert the same key and the second finds the first. Only automatic jobs:
-- a manual reprint is, by definition, a second print.
CREATE UNIQUE INDEX uq_print_job_auto_source ON printing.print_jobs (
    tenant_id, location_id, printer_id, document_type, source_type, source_id, source_revision,
    coalesce(station_id, '00000000-0000-0000-0000-000000000000'::uuid), copy_no)
    WHERE origin = 'AUTO';

-- Per printer, jobs are claimed one at a time in queued order, so a station's tickets keep their order.
CREATE INDEX ix_print_jobs_printer_queue ON printing.print_jobs (printer_id, queued_at)
    WHERE status IN ('QUEUED', 'CLAIMED');

-- The queue screen and the reconciler read a location's recent jobs by state.
CREATE INDEX ix_print_jobs_location ON printing.print_jobs (tenant_id, location_id, status, queued_at DESC);

-- The purge job finds what is past its retention without scanning the table.
CREATE INDEX ix_print_jobs_purge ON printing.print_jobs (payload_purge_after)
    WHERE protected_document IS NOT NULL;

-- A source's jobs, for the order action and for the sweeper's "what already exists" read.
CREATE INDEX ix_print_jobs_source ON printing.print_jobs (tenant_id, source_type, source_id);

COMMENT ON TABLE printing.print_jobs IS
    'ADR 0154. A durable, leased, at-least-once print job. protected_document is the ADR 0029 envelope of the document (a line note can hold anything a customer typed) and is erased 7 days after a terminal state and 30 days after a dead job; the row, its digest and its outcome stay. Never deleted.';
COMMENT ON COLUMN printing.print_jobs.source_id IS
    'A row in another module''s table (the ticket, the order, the fiscal document) and deliberately not a foreign key.';

-- ---------------------------------------------------------------------------
-- agent_printer_adverts
-- ---------------------------------------------------------------------------

CREATE TABLE printing.agent_printer_adverts (
    tenant_id uuid NOT NULL,
    location_id uuid NOT NULL,
    agent_device_id uuid NOT NULL,
    -- The alias the agent chose locally. The platform never sends the agent an address to dial.
    printer_key varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    -- The agent's own build string, as it last reported it: what a test page prints beside the printer's name.
    agent_build varchar(64),
    seen_at timestamptz NOT NULL,

    CONSTRAINT pk_agent_printer_advert PRIMARY KEY (tenant_id, agent_device_id, printer_key),
    CONSTRAINT ck_agent_printer_advert_status CHECK (
        status IN ('ONLINE', 'OFFLINE', 'OUT_OF_PAPER', 'COVER_OPEN', 'ERROR', 'UNKNOWN')),
    CONSTRAINT fk_agent_printer_advert_agent FOREIGN KEY (tenant_id, agent_device_id, location_id)
        REFERENCES iam.device_principals (tenant_id, id, location_id)
);

CREATE INDEX ix_agent_printer_adverts_location ON printing.agent_printer_adverts (tenant_id, location_id);

COMMENT ON TABLE printing.agent_printer_adverts IS
    'ADR 0154. The printers each print agent last said it can reach, by the alias the agent chose, and the status it reported. The console binds a printer row to one of these aliases.';

-- ---------------------------------------------------------------------------
-- Grants and row-level security
-- ---------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON printing.printers TO horecaos_application;
-- A route may be deleted (inert without jobs); a job never is, its payload is nulled instead.
GRANT SELECT, INSERT, UPDATE, DELETE ON printing.print_routes TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON printing.receipt_profiles TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON printing.print_jobs TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON printing.agent_printer_adverts TO horecaos_application;

SELECT platform.enable_tenant_row_level_security('printing.printers');
SELECT platform.enable_tenant_row_level_security('printing.print_routes');
SELECT platform.enable_tenant_row_level_security('printing.receipt_profiles');
SELECT platform.enable_tenant_row_level_security('printing.print_jobs');
SELECT platform.enable_tenant_row_level_security('printing.agent_printer_adverts');
