-- ADR 0141 (Accepted 2026-10-01), gap map row 2.5a: a stop is a record with a
-- scope, a source and an optional end.
--
-- This table is the "embargo" half of the two-kinds-of-not-sellable split the
-- ADR draws. The other half -- supply state, the BINARY boolean in
-- inventory.positions and the QUANTITY remaining count (ADR 0017, V0019) -- is
-- NOT touched here and is not replaced by this table: a variant is sellable at
-- (location, channel) when it is offered, its supply state allows it, no ACTIVE
-- embargo below covers that (variant, location, channel), and (QUANTITY only,
-- projection only) its remaining count is above the channel type's threshold
-- (V0407, unchanged). One resolver in inventory.application
-- (AvailabilityResolver) is the only reader of all of them.
--
-- Additive: an empty table changes nothing, which is the V0389/V0390 posture.
-- Every existing tenant is exactly as it was until somebody creates a stop.
--
-- Scope is four values and the union of covering embargoes stops the sale:
--   LOCATION  every channel at one branch
--   BRAND     every branch of the brand, including one bound later (no location
--             column to go stale: the covering test is "this variant is the
--             brand's, and the brand stopped it")
--   MENU      every (location, channel) whose resolved catalog.branch_menu_bindings
--             menu is menu_id (resolved at read through a catalog.api lookup, so
--             inventory never reads catalog tables)
--   CHANNEL   one tenant.sales_channels row, at one location or (location_id
--             NULL) everywhere it runs
-- There is no "allow" record that overrides a broader stop.
--
-- TERMINAL is a name the platform knows and refuses (the pattern V0034 used for
-- SETTLE_OPEN_TICKET): ADR 0141 Decision 3 folds a terminal into a CHANNEL
-- (ADR 0036 makes KIOSK, POS and QR_TABLE channel types) until a device
-- registry exists that can name two terminals in one channel. Adding the value
-- back is one line, in the migration that ships that registry.
--
-- Source is data, not a parsed reason string, and sources do not lift each
-- other (a POS "back in stock" ends only POS rows). KITCHEN_DEVICE and RULE are
-- in the vocabulary because ADR 0141 reserves them; the service refuses to
-- write them until ADR 0041's device write path and a rule engine exist.
--
-- reason_code is a short enumerated code, never free text (ADR 0029): it is
-- kept permanently in the audit trail, echoed on every read, and published on
-- inventory.events. source_ref is the integration.bindings id for POS and is
-- never a name or a phone.
--
-- Rows are never deleted: LIFTED and EXPIRED rows are the history. A BRAND stop
-- has no stock item, so the record is this row plus the ADR 0027 audit fact, not
-- a ledger row per branch.

CREATE TABLE inventory.availability_stops (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    variant_id uuid NOT NULL,

    scope_type varchar(16) NOT NULL,
    location_id uuid,
    menu_id uuid,
    channel_id uuid,

    source varchar(16) NOT NULL,
    source_ref varchar(64),
    reason_code varchar(64) NOT NULL,

    -- Evaluated at read: an ends_at in the past covers nothing whether or not
    -- the expiry sweeper has marked the row EXPIRED yet. The sweeper only writes
    -- the audit fact and the event; it is not on the correctness path of any
    -- read, nor of the marketplace reconciler's resync sweep (ADR 0141
    -- Decision 5 and 7).
    ends_at timestamptz,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',

    -- One gesture, many variants: a bulk stop, or a product-level stop written
    -- as a group of variant stops.
    group_id uuid,

    created_by varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    lifted_by varchar(128),
    lifted_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_stop_scope_type CHECK (scope_type IN ('LOCATION', 'BRAND', 'MENU', 'CHANNEL')),
    CONSTRAINT ck_stop_source CHECK (source IN ('OPERATOR', 'BOT', 'POS', 'KITCHEN_DEVICE', 'RULE')),
    CONSTRAINT ck_stop_status CHECK (status IN ('ACTIVE', 'LIFTED', 'EXPIRED')),
    CONSTRAINT ck_stop_version CHECK (version >= 1),
    CONSTRAINT ck_stop_reason_code CHECK (reason_code ~ '^[A-Z0-9_]{1,64}$'),

    -- The scope columns are present exactly as the scope names them. A stop with
    -- a location on a BRAND scope would silently mean either, depending on which
    -- reader looked at it.
    CONSTRAINT ck_stop_scope_columns CHECK (
        (scope_type = 'LOCATION' AND location_id IS NOT NULL AND menu_id IS NULL AND channel_id IS NULL)
        OR (scope_type = 'BRAND' AND location_id IS NULL AND menu_id IS NULL AND channel_id IS NULL)
        OR (scope_type = 'MENU' AND menu_id IS NOT NULL AND location_id IS NULL AND channel_id IS NULL)
        OR (scope_type = 'CHANNEL' AND channel_id IS NOT NULL AND menu_id IS NULL)),

    -- A lifted or expired row says when; an ACTIVE row never does.
    CONSTRAINT ck_stop_lifted_columns CHECK (
        (status = 'ACTIVE' AND lifted_at IS NULL AND lifted_by IS NULL)
        OR (status = 'LIFTED' AND lifted_at IS NOT NULL AND lifted_by IS NOT NULL)
        OR (status = 'EXPIRED' AND lifted_at IS NOT NULL)),

    CONSTRAINT fk_stop_variant FOREIGN KEY (variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),
    CONSTRAINT fk_stop_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_stop_menu FOREIGN KEY (menu_id, tenant_id, brand_id)
        REFERENCES catalog.menus (id, tenant_id, brand_id),
    CONSTRAINT fk_stop_channel FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id)
);

-- At most one ACTIVE stop per (variant, scope, source). NULLS NOT DISTINCT is
-- the point: location_id, menu_id, channel_id and source_ref are NULL for most
-- scopes, and a plain unique index treats every NULL as different, which would
-- let the same brand-wide stop be inserted twice and make "lift it" remove only
-- one of them. source_ref is part of the key so two POS bindings at one branch
-- keep independent opinions and a POS "back in stock" ends only its own binding's
-- row; for every other source it is NULL and the index is the ADR's.
CREATE UNIQUE INDEX ux_availability_stops_active
    ON inventory.availability_stops
        (tenant_id, variant_id, scope_type, location_id, menu_id, channel_id, source, source_ref)
    NULLS NOT DISTINCT
    WHERE status = 'ACTIVE';

-- The resolver's read: every ACTIVE stop on a set of variants.
CREATE INDEX ix_availability_stops_variant
    ON inventory.availability_stops (tenant_id, variant_id)
    WHERE status = 'ACTIVE';

-- The stop-list console's read: what is stopped in this brand, by scope.
CREATE INDEX ix_availability_stops_brand_scope
    ON inventory.availability_stops (tenant_id, brand_id, scope_type, status);

-- The expiry sweeper's read, across tenants.
CREATE INDEX ix_availability_stops_expiry
    ON inventory.availability_stops (ends_at)
    WHERE status = 'ACTIVE' AND ends_at IS NOT NULL;

CREATE INDEX ix_availability_stops_group
    ON inventory.availability_stops (tenant_id, group_id)
    WHERE group_id IS NOT NULL;

COMMENT ON TABLE inventory.availability_stops IS
    'ADR 0141, gap map row 2.5a. A stop is a record with a scope (LOCATION, BRAND, MENU, CHANNEL; TERMINAL is refused), a source (OPERATOR, BOT, POS; KITCHEN_DEVICE and RULE reserved) and an optional end. Read only through AvailabilityResolver, alongside ADR 0017''s position boolean, which this table does not replace. Rows are never deleted; LIFTED and EXPIRED are the history.';
COMMENT ON COLUMN inventory.availability_stops.ends_at IS
    'Evaluated at read. An ends_at in the past covers nothing whether or not the expiry sweeper has marked the row EXPIRED.';
COMMENT ON COLUMN inventory.availability_stops.source_ref IS
    'POS: the integration.bindings id. Never a name, a phone, or free text (ADR 0029).';

GRANT SELECT, INSERT, UPDATE, DELETE ON inventory.availability_stops TO horecaos_application;

-- ADR 0056: inventory is the schema that enforces row-level security, so the
-- module's next table does too. Every read and write of this table is therefore
-- in a transaction that has bound its tenant (InventoryService does, at the top
-- of every method); the expiry sweeper, which reaches every tenant by design,
-- assumes horecaos_platform_bypass the way expireStaleReservations does.
SELECT platform.enable_tenant_row_level_security('inventory.availability_stops');
