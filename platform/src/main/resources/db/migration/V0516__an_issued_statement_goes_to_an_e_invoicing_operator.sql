-- ADR 0096: an issued statement can be sent to an e-invoicing operator.
--
-- Four things land here and nothing else:
--
--   1. the approved endpoints of the two operators (ADR 0026's catalogue, which is
--      what closes the request-forgery path -- nothing is ever called at a URL
--      somebody typed);
--   2. HorecaOS's own accounts with them, as PLATFORM installations;
--   3. the classification code and VAT rate each statement line kind is invoiced
--      under, as configuration with PROVISIONAL defaults;
--   4. the record of what was sent for a statement, the operator's document id and
--      the state the operator reports.
--
-- No signature material is stored anywhere below, and none is ever received: a
-- document is sent as a draft and signed by people inside the operator's own
-- product (ADR 0096 decision 3).

-- ---------------------------------------------------------------- 1. endpoints
--
-- Category OTHER, on purpose. ProviderCategory has no E_INVOICING value, and the
-- two CHECK constraints that would have to name one (V0013, restated by V0038,
-- V0145 and V0250) are restated in full by every migration that touches them, so
-- two parallel waves each adding a category silently remove each other's. The
-- provider type is what an adapter matches on; the category is only a coarse
-- label, and OTHER already means "an external system with no category of its own".
--
-- Didox: the production host is the partner API's, and the stage host is the one
-- the partner SDK (npm `didox`, config `environment`) selects for non-production.
-- Faktura.uz: https://api.faktura.uz carries every functional call and
-- https://account.faktura.uz issues the bearer token (both named in the operator's
-- published Swagger description, api.faktura.uz/swagger), so the allowlist names
-- both hosts and the gateway refuses a call to any host not on it.
INSERT INTO integration.provider_environments
    (code, provider_category, provider_type, base_url, is_production, egress_allowlist, notes)
VALUES
    ('didox_production', 'OTHER', 'DIDOX',
     'https://api-partners.didox.uz', true, 'api-partners.didox.uz',
     'ADR 0096. Didox partner API. The account is HorecaOS''s own (a platform installation), never a tenant''s.'),
    ('didox_stage', 'OTHER', 'DIDOX',
     'https://stage.goodsign.biz', false, 'stage.goodsign.biz',
     'ADR 0096. Didox''s non-production host as the partner SDK selects it. Documents sent here are not legal documents.'),
    ('faktura_uz_production', 'OTHER', 'FAKTURA_UZ',
     'https://api.faktura.uz', true, 'api.faktura.uz,account.faktura.uz',
     'ADR 0096. Faktura.uz API (functional calls) and its account host (token endpoint). No non-production sibling: the operator publishes none.')
ON CONFLICT (code) DO NOTHING;

-- ------------------------------------------------- 2. platform installations
--
-- integration.installations is tenant-owned (tenant_id NOT NULL, V0013), and the
-- ControlPlaneAlertPort comment records why a synthetic "platform tenant" row to
-- hold HorecaOS's own accounts was refused: it would pollute every tenant listing
-- and billing surface. So HorecaOS's own provider account is a row of its own,
-- with the same fields ADR 0026 gives an installation -- provider type,
-- approved environment, status, a secret REFERENCE (ADR 0028), non-sensitive
-- configuration, adapter version, an expected-version counter -- and no tenant.
--
-- The rows below are seeded UNBOUND: DRAFT, no secret reference, no seller
-- details. HorecaOS has no account with either operator yet, and an unbound
-- installation is exactly what the control plane says so about. An installation
-- cannot be ACTIVE without a secret reference (ck_einvoicing_installation_active).
CREATE TABLE commercial.einvoicing_installations (
    id uuid PRIMARY KEY,
    provider_type varchar(32) NOT NULL,
    environment_code varchar(64) NOT NULL,
    display_name varchar(200) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    -- A reference, never a value (ADR 0028). Written by an operator with
    -- `bao kv put`; the value is one JSON object of the operator's login fields.
    secret_reference varchar(512),
    -- The seller's identity as it appears on the invoice (tin, name, address, VAT
    -- registration code, bank account and MFO, signing people). Public business
    -- identifiers, never a credential.
    non_sensitive_config jsonb NOT NULL DEFAULT '{}'::jsonb,
    adapter_version varchar(64) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    updated_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_einvoicing_installation_environment FOREIGN KEY (environment_code)
        REFERENCES integration.provider_environments (code),
    CONSTRAINT uq_einvoicing_installation_environment UNIQUE (provider_type, environment_code),
    CONSTRAINT ck_einvoicing_installation_provider CHECK (provider_type IN ('DIDOX', 'FAKTURA_UZ')),
    CONSTRAINT ck_einvoicing_installation_status CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_einvoicing_installation_config CHECK (jsonb_typeof(non_sensitive_config) = 'object'),
    CONSTRAINT ck_einvoicing_installation_active CHECK (status <> 'ACTIVE' OR secret_reference IS NOT NULL),
    CONSTRAINT ck_einvoicing_installation_version CHECK (version >= 0)
);

-- One live account per operator: two ACTIVE installations of one operator would
-- make "which account sent this" a question about row order.
CREATE UNIQUE INDEX ux_einvoicing_installation_active
    ON commercial.einvoicing_installations (provider_type) WHERE status = 'ACTIVE';

COMMENT ON TABLE commercial.einvoicing_installations IS
    'ADR 0096. HorecaOS''s own accounts with the e-invoicing operators: platform installations (no tenant), a secret reference and the seller identity. Seeded unbound.';

INSERT INTO commercial.einvoicing_installations
    (id, provider_type, environment_code, display_name, status, secret_reference, non_sensitive_config,
     adapter_version, version, updated_by)
VALUES
    ('018f9c10-5000-7000-8000-0000000000d1', 'DIDOX', 'didox_production', 'Didox', 'DRAFT', NULL, '{}'::jsonb,
     'didox-partner-api-v1', 0, 'migration V0516'),
    ('018f9c10-5000-7000-8000-0000000000f1', 'FAKTURA_UZ', 'faktura_uz_production', 'Faktura.uz', 'DRAFT', NULL,
     '{}'::jsonb, 'faktura-api-v1', 0, 'migration V0516')
ON CONFLICT DO NOTHING;

GRANT SELECT, INSERT, UPDATE ON commercial.einvoicing_installations TO horecaos_application;

-- ------------------------------------------- 3. classification and VAT by kind
--
-- PROVISIONAL, every row. Nobody in finance has stated these (ADR 0096's open
-- input, and ADR 0088 still says "tax on a subscription ... is finance's to
-- state"). They were chosen by engineering so that a document is complete
-- enough to build and test, and a row stays flagged `provisional` until finance
-- confirms or replaces it (`confirmed_by` / `confirmed_at` are then set):
--
--   * catalog_code / catalog_name -- the ИКПУ (national product classifier)
--     code of the thing invoiced. NOT verified against the national catalogue.
--   * package_code / package_name -- the unit of measure's catalogue code.
--     NOT verified.
--   * vat_rate_bp -- 1200 (12%), the standard rate, ADDED ON TOP of the
--     statement's before-tax amounts. Whether the statement amounts are net or
--     gross of VAT is also finance's call.
--
-- The seller's own VAT registration decides whether VAT is charged at all; with
-- no VAT registration code configured on the installation the document is sent
-- without VAT, whatever is set here.
CREATE TABLE commercial.einvoicing_line_classifications (
    line_kind varchar(16) PRIMARY KEY,
    item_label varchar(200) NOT NULL,
    catalog_code varchar(32) NOT NULL,
    catalog_name varchar(200) NOT NULL,
    package_code varchar(32) NOT NULL,
    package_name varchar(100) NOT NULL,
    vat_rate_bp integer NOT NULL,
    provisional boolean NOT NULL DEFAULT true,
    confirmed_by varchar(255),
    confirmed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    updated_by varchar(255) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_einvoicing_line_kind CHECK (line_kind IN ('PLAN', 'MODULE', 'OVERAGE', 'EARLY_EXIT', 'DEPOSIT')),
    CONSTRAINT ck_einvoicing_line_vat CHECK (vat_rate_bp BETWEEN 0 AND 10000),
    CONSTRAINT ck_einvoicing_line_confirmed CHECK (
        provisional = (confirmed_at IS NULL) AND (confirmed_at IS NULL) = (confirmed_by IS NULL)),
    CONSTRAINT ck_einvoicing_line_version CHECK (version >= 0)
);

COMMENT ON TABLE commercial.einvoicing_line_classifications IS
    'ADR 0096. The product classification code and VAT rate each statement line kind is invoiced under. Seeded PROVISIONAL: finance has not stated them.';

INSERT INTO commercial.einvoicing_line_classifications
    (line_kind, item_label, catalog_code, catalog_name, package_code, package_name, vat_rate_bp, provisional,
     updated_by)
VALUES
    ('PLAN', 'Подписка на платформу HorecaOS (тарифный план)', '10305011001000000',
     'Услуги по предоставлению доступа к программному обеспечению', '1500002', 'услуга', 1200, true,
     'migration V0516'),
    ('MODULE', 'Подписка на платформу HorecaOS (модуль)', '10305011001000000',
     'Услуги по предоставлению доступа к программному обеспечению', '1500002', 'услуга', 1200, true,
     'migration V0516'),
    ('OVERAGE', 'Платформа HorecaOS: использование сверх тарифа', '10305011001000000',
     'Услуги по предоставлению доступа к программному обеспечению', '1500002', 'услуга', 1200, true,
     'migration V0516'),
    ('EARLY_EXIT', 'Платформа HorecaOS: возврат скидки за срок при досрочном выходе', '10305011001000000',
     'Услуги по предоставлению доступа к программному обеспечению', '1500002', 'услуга', 1200, true,
     'migration V0516'),
    ('DEPOSIT', 'Платформа HorecaOS: депозит активации', '10305011001000000',
     'Услуги по предоставлению доступа к программному обеспечению', '1500002', 'услуга', 1200, true,
     'migration V0516')
ON CONFLICT DO NOTHING;

GRANT SELECT, INSERT, UPDATE ON commercial.einvoicing_line_classifications TO horecaos_application;

-- ------------------------------------------------------ 4. what was sent
--
-- One row per attempt to send one issued statement to one operator. It records
-- what HorecaOS sent (the provider-neutral document, frozen below), the document
-- identifier the operator gave back and the state the operator last reported --
-- as the operator reports it, in `operator_status`, beside the canonical reading
-- of it in `operator_state`.
--
-- `delivery` is OUR side of the attempt:
--   PENDING    written before the operator is called, so the attempt is durable
--              before anything can have been sent (the card-charge pattern, V0214)
--   SUBMITTED  the operator accepted the draft and named it (operator_document_id)
--   FAILED     the operator was not reached or refused it: nothing is held there
--   UNCERTAIN  the operator may or may not hold it; reconciled by asking, never by
--              sending again (ADR 0007)
--
-- A statement has at most one LIVE document across both operators
-- (ux_einvoice_live): sending the same month to Didox and to Faktura.uz would
-- invoice the tenant twice. A document the operator refused or cancelled, and an
-- attempt that failed, stop being live, so the statement can be sent again.
CREATE TABLE commercial.statement_einvoices (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    statement_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    provider_type varchar(32) NOT NULL,
    -- The buyer: the tenant's legal entity and its tax number, as they were when sent.
    legal_entity_id uuid NOT NULL,
    buyer_tin varchar(14) NOT NULL,
    buyer_name varchar(200) NOT NULL,
    seller_tin varchar(14) NOT NULL,
    document_number varchar(64) NOT NULL,
    document_date date NOT NULL,
    currency char(3) NOT NULL,
    net_minor bigint NOT NULL,
    vat_minor bigint NOT NULL,
    total_minor bigint NOT NULL,
    -- Whether any line kind in this document was still invoiced under a
    -- provisional classification when it was sent.
    classification_provisional boolean NOT NULL,
    -- The provider-neutral document as built, frozen.
    sent_document jsonb NOT NULL,
    delivery varchar(16) NOT NULL,
    failure_code varchar(64),
    failure_detail varchar(500),
    operator_document_id varchar(128),
    operator_state varchar(16),
    operator_status varchar(128),
    state_checked_at timestamptz,
    state_changed_at timestamptz,
    send_reason varchar(1000) NOT NULL,
    sent_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_einvoice_statement FOREIGN KEY (tenant_id, statement_id)
        REFERENCES commercial.statements (tenant_id, id),
    CONSTRAINT fk_einvoice_installation FOREIGN KEY (installation_id)
        REFERENCES commercial.einvoicing_installations (id),
    CONSTRAINT ck_einvoice_provider CHECK (provider_type IN ('DIDOX', 'FAKTURA_UZ')),
    CONSTRAINT ck_einvoice_delivery CHECK (delivery IN ('PENDING', 'SUBMITTED', 'FAILED', 'UNCERTAIN')),
    CONSTRAINT ck_einvoice_state CHECK (
        operator_state IS NULL OR operator_state IN ('DRAFT', 'SENT', 'SIGNED', 'REFUSED', 'CANCELLED', 'UNKNOWN')),
    CONSTRAINT ck_einvoice_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_einvoice_amounts CHECK (
        net_minor >= 0 AND vat_minor >= 0 AND total_minor = net_minor + vat_minor),
    CONSTRAINT ck_einvoice_jsonb CHECK (jsonb_typeof(sent_document) = 'object'),
    -- A submitted document has the operator's identifier and a state; a failed
    -- attempt names why it failed.
    CONSTRAINT ck_einvoice_submitted CHECK (
        delivery <> 'SUBMITTED' OR (operator_document_id IS NOT NULL AND operator_state IS NOT NULL)),
    CONSTRAINT ck_einvoice_failed CHECK (delivery <> 'FAILED' OR failure_code IS NOT NULL),
    CONSTRAINT ck_einvoice_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX ux_einvoice_live ON commercial.statement_einvoices (tenant_id, statement_id)
    WHERE delivery IN ('PENDING', 'SUBMITTED', 'UNCERTAIN')
      AND (operator_state IS NULL OR operator_state NOT IN ('REFUSED', 'CANCELLED'));
CREATE INDEX ix_einvoice_statement ON commercial.statement_einvoices (tenant_id, statement_id, created_at DESC);
CREATE INDEX ix_einvoice_open ON commercial.statement_einvoices (state_checked_at NULLS FIRST)
    WHERE delivery IN ('SUBMITTED', 'UNCERTAIN')
      AND (operator_state IS NULL OR operator_state NOT IN ('SIGNED', 'REFUSED', 'CANCELLED'));

COMMENT ON TABLE commercial.statement_einvoices IS
    'ADR 0096. One attempt to send one issued statement to an e-invoicing operator: what was sent, the operator''s document id and the state it reports. Never signature material.';

-- What was sent is evidence, and so is whom it was sent to: only the state the
-- operator reports and our own delivery bookkeeping ever move afterwards.
CREATE OR REPLACE FUNCTION commercial.reject_einvoice_rewrite() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'A sent e-invoice record is never deleted (ADR 0096)';
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.statement_id IS DISTINCT FROM OLD.statement_id
        OR NEW.installation_id IS DISTINCT FROM OLD.installation_id
        OR NEW.provider_type IS DISTINCT FROM OLD.provider_type
        OR NEW.legal_entity_id IS DISTINCT FROM OLD.legal_entity_id
        OR NEW.buyer_tin IS DISTINCT FROM OLD.buyer_tin
        OR NEW.buyer_name IS DISTINCT FROM OLD.buyer_name
        OR NEW.seller_tin IS DISTINCT FROM OLD.seller_tin
        OR NEW.document_number IS DISTINCT FROM OLD.document_number
        OR NEW.document_date IS DISTINCT FROM OLD.document_date
        OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.net_minor IS DISTINCT FROM OLD.net_minor
        OR NEW.vat_minor IS DISTINCT FROM OLD.vat_minor
        OR NEW.total_minor IS DISTINCT FROM OLD.total_minor
        OR NEW.classification_provisional IS DISTINCT FROM OLD.classification_provisional
        OR NEW.sent_document IS DISTINCT FROM OLD.sent_document
        OR NEW.send_reason IS DISTINCT FROM OLD.send_reason
        OR NEW.sent_by IS DISTINCT FROM OLD.sent_by
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'What was sent to an e-invoicing operator is never rewritten (ADR 0096)';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_einvoice_rewrite
    BEFORE UPDATE OR DELETE ON commercial.statement_einvoices
    FOR EACH ROW EXECUTE FUNCTION commercial.reject_einvoice_rewrite();

-- A statement that is standing as an invoice at an operator is not voided from
-- underneath it: cancelling the invoice happens at the operator (it needs its
-- signers), and the statement follows once the operator says it is cancelled or
-- refused. The service checks this first and says so; this is the backstop.
CREATE OR REPLACE FUNCTION commercial.reject_void_of_statement_with_live_einvoice() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'ISSUED' AND NEW.status = 'VOID' AND EXISTS (
        SELECT 1 FROM commercial.statement_einvoices e
         WHERE e.tenant_id = OLD.tenant_id AND e.statement_id = OLD.id
           AND e.delivery IN ('PENDING', 'SUBMITTED', 'UNCERTAIN')
           AND (e.operator_state IS NULL OR e.operator_state NOT IN ('REFUSED', 'CANCELLED'))) THEN
        RAISE EXCEPTION 'A statement with a live e-invoice at an operator is not voided; cancel the invoice there first (ADR 0096)';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_statements_void_needs_no_live_einvoice
    BEFORE UPDATE ON commercial.statements
    FOR EACH ROW EXECUTE FUNCTION commercial.reject_void_of_statement_with_live_einvoice();

GRANT SELECT, INSERT, UPDATE ON commercial.statement_einvoices TO horecaos_application;
