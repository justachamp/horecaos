-- ADR 0012: the stop-list's own cadence.
--
-- integration.pos_staged_availability (V0037) is scoped to one run_id and
-- cascade-deletes with it, which is right for the daily reviewed import but
-- wrong for the fast feed: GET /products/stop-list is the one endpoint in the
-- Clopos API carrying a per-row change timestamp, it covers the fastest-moving
-- data a restaurant has, and ADR 0012 is explicit that it "runs on its own
-- cadence, roughly every thirty to sixty seconds, while the catalog structure
-- runs daily as the reviewed run" and that "collapsing them into one daily run
-- makes the stop list useless". Forcing the fast poll to open a pos_sync_runs
-- row every thirty seconds would make every run row mean two different things
-- and would drag the removal quorum's absence bookkeeping into a feed that has
-- nothing to do with it. This table is the separation made real: current
-- provider-stated availability, replaced wholesale on every poll, with no run
-- lifecycle at all.
--
-- No run_id. No history. The current stop-list reading is the only fact this
-- table exists to hold; ADR 0012's difference-and-review machinery is what
-- reasons about change over time, and it already does that from the daily
-- run's own pos_staged_availability snapshot.
CREATE TABLE integration.pos_live_availability (
    tenant_id uuid NOT NULL,
    binding_id uuid NOT NULL,
    external_entity_id varchar(64) NOT NULL,

    -- Null means the provider named no limit for this entity right now.
    -- Absence from the stop list means unconstrained, not unavailable, and
    -- inverting that reading empties the entire menu -- see
    -- pos_staged_availability's own comment for the same rule.
    stock_limit numeric(18, 3),
    -- The stop list's own per-row timestamp, milliseconds on Clopos. Null when
    -- the provider's timestamp field did not parse, which is recorded rather
    -- than defaulted so a genuinely unknown observation time is distinct from
    -- one that happened to be the epoch.
    observed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_pos_live_availability PRIMARY KEY (binding_id, external_entity_id),
    CONSTRAINT fk_pos_live_availability_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_pos_live_availability_limit CHECK (stock_limit IS NULL OR stock_limit >= 0)
);

CREATE INDEX ix_pos_live_availability_tenant
    ON integration.pos_live_availability (tenant_id, binding_id);

COMMENT ON TABLE integration.pos_live_availability IS
    'ADR 0012. The stop list''s own fast cadence (~30-60s), replaced wholesale per binding on every poll. Not run-scoped: it has no lifecycle, no review, and no removal quorum -- it is simply what the provider says right now.';

COMMENT ON COLUMN integration.pos_live_availability.stock_limit IS
    'Zero means out of stock. Null means the provider named no limit, which is unconstrained and emphatically not unavailable.';

GRANT SELECT, INSERT, UPDATE, DELETE ON integration.pos_live_availability TO horecaos_application;
