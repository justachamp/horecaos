-- ADR 0038 lines 503-513: the terminal registry the sketch named and nobody
-- built. Settings 10.7 Tab 2 has had a schema to render and nothing to call
-- since the tab was written; `CheckoutSettlementPlanner.responsibilityOf`
-- has registered every cash tender as OPERATOR, which its own doc comment
-- calls the wrong direction, because there was nothing here to ask whether a
-- location actually has fiscal-capable equipment.
--
-- A terminal is a POS, a courier's handheld unit, a kiosk, or a virtual
-- terminal issued for a location with none of the above -- ADR 0038's own
-- words: "a kiosk is a terminal of kind KIOSK bound to a location and
-- resolving to that location's legal entity, the whole of kiosk fiscal
-- identity, at the cost of one row." That is why this table names a location
-- and a legal entity rather than only a brand: fiscal identity is
-- branch-granular in this market (tenant.legal_entities' own comment says the
-- same), and a terminal issues receipts on behalf of whichever company
-- currently sells at the branch it sits in.

CREATE TABLE fiscal.fiscal_terminals (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    legal_entity_id uuid NOT NULL,

    terminal_kind varchar(20) NOT NULL,

    -- ADR 0026. Nullable: a terminal can be registered before its provider
    -- side is wired (a courier handheld ordered but not yet paired), and a
    -- VIRTUAL terminal issued directly against a legal entity may have no
    -- provider account behind it at all. A terminal with no binding is never
    -- fiscal-capable regardless of what its capability_snapshot claims -- see
    -- ck_fiscal_terminal_capable_needs_binding below.
    provider_binding_id uuid,

    -- What the tenant's own paperwork calls this box: a serial number, an
    -- inventory tag, "Kassa 2 -- zal". Never a URL and never a credential; the
    -- endpoint always comes from provider_binding_id's own
    -- integration.provider_environments row, which is the model-level fix for
    -- the request-forgery path a free-typed endpoint would open.
    terminal_reference varchar(128) NOT NULL,

    -- What this terminal can actually do, keyed by capability name the same
    -- way integration.installations.capability_snapshot is. The one this
    -- migration's own callers read is "IssueFiscalReceipt": whether this
    -- terminal can be asked for a fiscal receipt at all right now.
    capability_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,

    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    last_health_check_at timestamptz,
    last_health_status varchar(16),

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_fiscal_terminal_kind CHECK (
        terminal_kind IN ('POS', 'COURIER_TERMINAL', 'KIOSK', 'VIRTUAL')),
    CONSTRAINT ck_fiscal_terminal_status CHECK (
        status IN ('ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_fiscal_terminal_health_status CHECK (
        last_health_status IS NULL OR last_health_status IN ('HEALTHY', 'UNHEALTHY')),
    -- A checked-at with no checked-status is a health check that ran and
    -- recorded nothing, which is worse than never having run one: a
    -- worklist sorted "never checked (1)" ahead of "failing (0)" per
    -- settings.md would rank this row wrong in both directions at once.
    CONSTRAINT ck_fiscal_terminal_health_pair CHECK (
        (last_health_check_at IS NULL) = (last_health_status IS NULL)),
    CONSTRAINT ck_fiscal_terminal_capabilities CHECK (
        jsonb_typeof(capability_snapshot) = 'object'),
    CONSTRAINT ck_fiscal_terminal_reference CHECK (
        length(btrim(terminal_reference)) > 0),
    CONSTRAINT ck_fiscal_terminal_version CHECK (version >= 1),

    -- A terminal that claims it can issue receipts must actually be wired to
    -- a provider. Otherwise "IssueFiscalReceipt": true is an operator's
    -- unchecked claim rather than a verified capability, and
    -- responsibilityOf would declare TERMINAL for a box that cannot reach a
    -- fiscal register.
    CONSTRAINT ck_fiscal_terminal_capable_needs_binding CHECK (
        NOT (capability_snapshot @> '{"IssueFiscalReceipt": true}'::jsonb)
        OR provider_binding_id IS NOT NULL),

    CONSTRAINT fk_fiscal_terminal_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_fiscal_terminal_legal_entity FOREIGN KEY (tenant_id, legal_entity_id)
        REFERENCES tenant.legal_entities (tenant_id, id),
    CONSTRAINT fk_fiscal_terminal_binding FOREIGN KEY (tenant_id, provider_binding_id)
        REFERENCES integration.bindings (tenant_id, id),

    -- Stable within a tenant, so an operator can tell two POS boxes apart in
    -- a list without opening either row.
    CONSTRAINT uq_fiscal_terminal_reference UNIQUE (tenant_id, terminal_reference)
);

COMMENT ON TABLE fiscal.fiscal_terminals IS
    'ADR 0038 lines 503-513. The equipment that discharges the TERMINAL fiscal responsibility: cash, courier-terminal, kiosk and dine-in POS settlement. Sketched at ADR acceptance and migrated here, wave P34.';
COMMENT ON COLUMN fiscal.fiscal_terminals.capability_snapshot IS
    'Keyed by capability name, as integration.installations.capability_snapshot is. "IssueFiscalReceipt": true is what CheckoutSettlementPlanner and the activation precondition both read as "this location has a fiscal-capable terminal".';
COMMENT ON COLUMN fiscal.fiscal_terminals.last_health_status IS
    'Written only by the operator-triggered "Проверить связь" action (settings.md Tab 2). Never swept on a timer in this wave.';

-- The query CheckoutSettlementPlanner and the activation precondition both
-- ask: does this location have at least one active, fiscal-capable terminal.
-- Partial on the predicate both callers filter by, so the answer is an index
-- lookup rather than a scan of every terminal a tenant has ever registered.
CREATE INDEX ix_fiscal_terminals_capable_by_location
    ON fiscal.fiscal_terminals (tenant_id, location_id)
    WHERE status = 'ACTIVE' AND capability_snapshot @> '{"IssueFiscalReceipt": true}';

-- The list view's own ordering (settings.md: failing health (0) -> never
-- checked (1) -> healthy (2)) and the register/list reads, both tenant-scoped.
CREATE INDEX ix_fiscal_terminals_tenant_brand
    ON fiscal.fiscal_terminals (tenant_id, brand_id);

-- No DELETE: a terminal that stops existing is RETIRED, not removed, because
-- a fiscal document already issued through it must still resolve which box
-- issued it years later -- the same evidentiary reason fiscal.fiscal_documents
-- carries no DELETE grant either.
GRANT SELECT, INSERT, UPDATE ON fiscal.fiscal_terminals TO horecaos_application;
