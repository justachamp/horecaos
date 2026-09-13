-- ADR 0038, row 10.6: the rest of the registry a settings screen needs.
--
-- payments.payment_methods (V0042) has always had a code, a display name, a
-- fiscal responsibility, the balance-settlement flag and a status. It has
-- never had a place to put a display icon, a sort position among a tenant's
-- other methods, or which ADR 0026 provider installation actually settles a
-- PARTNER-responsibility method (Click, Payme) — every tenant registering
-- CLICK today shares one code-owned enum entry and no installation of its
-- own, so nothing before this migration could tell one tenant's Click
-- merchant account from another's at the registry level (that binding lives
-- one layer down, on payments.merchant_bindings, ADR 0013's per-legal-entity
-- concern; this column is the tenant-level default the registry itself
-- names, the same "default vs. per-entity" split ADR 0030 already draws
-- elsewhere).
ALTER TABLE payments.payment_methods
    ADD COLUMN icon varchar(64),
    ADD COLUMN sort_order integer NOT NULL DEFAULT 0,
    -- ADR 0026 installation this method settles through by default. Nullable:
    -- CASH (OPERATOR) and a MARKETPLACE-responsibility method never have one.
    ADD COLUMN provider_installation_id uuid,
    -- The acquirer's own contract or merchant-agreement number, printed on
    -- reconciliation paperwork. A reference for a human to match against a
    -- statement, never a credential — ADR 0028 secrets stay on the
    -- installation and on payments.merchant_bindings.secret_reference, not
    -- here.
    ADD COLUMN contract_reference varchar(120);

ALTER TABLE payments.payment_methods
    ADD CONSTRAINT ck_payment_method_sort_order CHECK (sort_order >= 0);

-- Matched on (tenant_id, id) rather than provider_installation_id alone, the
-- same discipline V0020's fk_sales_channel_provider_installation follows: a
-- tenant may only bind its own installation, never another tenant's account.
ALTER TABLE payments.payment_methods
    ADD CONSTRAINT fk_payment_method_provider_installation
        FOREIGN KEY (tenant_id, provider_installation_id)
        REFERENCES integration.installations (tenant_id, id);

-- The tenant-scoped GET (10.4b's matrix columns, 10.6's own list) orders by
-- this, falling back to code for two methods sharing a position.
CREATE INDEX ix_payment_methods_tenant_order ON payments.payment_methods (tenant_id, sort_order, code);

COMMENT ON COLUMN payments.payment_methods.icon IS
    'A code-owned icon identifier the console renders, e.g. "cash", "click", "payme". Free text: the console tolerates an unknown value by falling back to a generic icon rather than refusing the row.';
COMMENT ON COLUMN payments.payment_methods.sort_order IS
    'Display position among this tenant''s own methods. Not unique: two rows sharing a position break the tie on code, deterministically.';
COMMENT ON COLUMN payments.payment_methods.provider_installation_id IS
    'The ADR 0026 installation this method settles through by default. Distinct from payments.merchant_bindings, which binds a legal entity rather than the tenant-wide registry row.';
