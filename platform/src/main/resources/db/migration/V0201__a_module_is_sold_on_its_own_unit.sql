-- ADR 0087: sellable modules, each billed on its own unit, and the tenants
-- that have one.
--
-- A module is a price-list entry beside the plans: it names what it is billed
-- per (the tenant, each brand, each branch, each unit such as a kiosk, or once)
-- and which feature entitlements it switches on. Like a plan version it is
-- drafted by one person and activated by another, and once active its terms
-- never change: a statement issued last spring has to read the same price
-- that was sold.

CREATE TABLE commercial.modules (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL,
    name varchar(200) NOT NULL,
    description varchar(1000),
    billing_unit varchar(16) NOT NULL,
    currency char(3) NOT NULL,
    unit_price_minor bigint NOT NULL,
    feature_keys text[] NOT NULL DEFAULT '{}',
    status varchar(16) NOT NULL,
    created_by varchar(255) NOT NULL,
    approved_by varchar(255),
    activated_at timestamptz,
    retired_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_module_code UNIQUE (code),
    CONSTRAINT ck_module_code CHECK (code ~ '^[a-z][a-z0-9_-]*$'),
    CONSTRAINT ck_module_billing_unit CHECK (
        billing_unit IN ('PER_TENANT', 'PER_BRAND', 'PER_LOCATION', 'PER_UNIT', 'ONE_OFF')
    ),
    CONSTRAINT ck_module_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_module_price CHECK (unit_price_minor >= 0),
    CONSTRAINT ck_module_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_module_four_eyes CHECK (approved_by IS NULL OR approved_by <> created_by),
    CONSTRAINT ck_module_activation_pair CHECK ((approved_by IS NULL) = (activated_at IS NULL)),
    CONSTRAINT ck_module_live_is_approved CHECK (status = 'DRAFT' OR activated_at IS NOT NULL),
    CONSTRAINT ck_module_retired_pair CHECK ((status = 'RETIRED') = (retired_at IS NOT NULL))
);

COMMENT ON TABLE commercial.modules IS
    'ADR 0087. Sellable modules beside the plans, each billed on its own unit. Immutable once activated; retiring stops new sales and leaves existing tenants as they are.';

-- The same rule as a plan version (V0033): once a second person has approved
-- the terms, nothing about them changes. Retiring is the one move left.
CREATE OR REPLACE FUNCTION commercial.reject_activated_module_change() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.activated_at IS NOT NULL THEN
            RAISE EXCEPTION 'An activated module is never deleted; retire it (ADR 0087)';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.activated_at IS NOT NULL
        AND (NEW.code IS DISTINCT FROM OLD.code
             OR NEW.name IS DISTINCT FROM OLD.name
             OR NEW.billing_unit IS DISTINCT FROM OLD.billing_unit
             OR NEW.currency IS DISTINCT FROM OLD.currency
             OR NEW.unit_price_minor IS DISTINCT FROM OLD.unit_price_minor
             OR NEW.feature_keys IS DISTINCT FROM OLD.feature_keys
             OR NEW.created_by IS DISTINCT FROM OLD.created_by
             OR NEW.approved_by IS DISTINCT FROM OLD.approved_by
             OR NEW.activated_at IS DISTINCT FROM OLD.activated_at) THEN
        RAISE EXCEPTION 'Activated module terms are immutable; draft a new module instead (ADR 0087)';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_modules_immutable_after_activation
    BEFORE UPDATE OR DELETE ON commercial.modules
    FOR EACH ROW EXECUTE FUNCTION commercial.reject_activated_module_change();

CREATE TABLE commercial.tenant_modules (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    module_id uuid NOT NULL,
    quantity integer,
    started_at timestamptz NOT NULL,
    started_by varchar(255) NOT NULL,
    start_reason varchar(1000) NOT NULL,
    ended_at timestamptz,
    ended_by varchar(255),
    end_reason varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_tenant_module_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_tenant_module_module FOREIGN KEY (module_id)
        REFERENCES commercial.modules (id),
    CONSTRAINT ck_tenant_module_quantity CHECK (quantity IS NULL OR quantity > 0),
    CONSTRAINT ck_tenant_module_end CHECK (
        (ended_at IS NULL) = (ended_by IS NULL) AND (ended_at IS NULL) = (end_reason IS NULL)
    ),
    CONSTRAINT ck_tenant_module_window CHECK (ended_at IS NULL OR ended_at >= started_at)
);

-- One live instance of a module per tenant: a second kiosk is a quantity,
-- not a second row.
CREATE UNIQUE INDEX ux_tenant_module_live ON commercial.tenant_modules (tenant_id, module_id)
    WHERE ended_at IS NULL;
CREATE INDEX ix_tenant_module_tenant ON commercial.tenant_modules (tenant_id, started_at);

COMMENT ON TABLE commercial.tenant_modules IS
    'ADR 0087. Which modules a tenant has, from when, and on whose word. Ending one keeps the row: a statement for a past month still has to find it.';

GRANT SELECT, INSERT, UPDATE ON commercial.modules TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON commercial.tenant_modules TO horecaos_application;
