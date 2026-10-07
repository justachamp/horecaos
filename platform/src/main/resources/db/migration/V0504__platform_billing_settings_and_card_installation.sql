-- ADR 0095 (Accepted 2026-10-07), the two inputs that were open and the defaults the owner took:
--
--   * "the bank details an invoice shows (finance)": a PLATFORM SETTING that starts as a
--     placeholder, which the control plane replaces. It is one row because HorecaOS has one
--     receiving account at a time, and it is never typed by one person: real details only exist on
--     a row that names a requester AND a different approver (ck_platform_billing_four_eyes), so
--     swapping the account every invoice tells a tenant to pay into -- the classic invoice fraud --
--     needs two people, exactly like every other manual change to a tenant's money (decision 4).
--
--   * "HorecaOS's own Click or Payme merchant account ... (finance, operations)": no such account
--     exists, so the card adapter is built against a fake and the real connection is an
--     installation the owner completes later. Installations (ADR 0026) are tenant-owned
--     (integration.installations.tenant_id is NOT NULL and a synthetic platform tenant was refused
--     on purpose, see notifications.api.ControlPlaneAlertPort), so HorecaOS's own account is the
--     same shape in its own table: provider type, an environment from the APPROVED catalogue (never
--     a typed URL), a secret REFERENCE (ADR 0028) and never a value, non-sensitive configuration,
--     status, connection evidence. At most one is ACTIVE.
--
--     The fake has no endpoint, so it names no environment; any other provider must name one.
--     Activating the fake outside a local or test run is refused by the application, not only
--     by convention (horecaos.commercial.card.allow-fake).

CREATE TABLE commercial.platform_billing_settings (
    -- A single row, enforced by the key itself rather than by anyone remembering to update it.
    singleton boolean PRIMARY KEY DEFAULT true,
    bank_beneficiary varchar(255) NOT NULL,
    bank_name varchar(255) NOT NULL,
    bank_account varchar(64) NOT NULL,
    bank_mfo varchar(32) NOT NULL,
    bank_tax_id varchar(32) NOT NULL,
    -- false while the row still holds the placeholder. An invoice is refused until it is true, so a
    -- tenant is never handed a document that tells it to pay a sentence.
    configured boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0,
    updated_by varchar(255) NOT NULL,
    updated_at timestamptz NOT NULL,
    approved_by varchar(255),
    approval_request_id uuid,
    CONSTRAINT ck_platform_billing_singleton CHECK (singleton),
    CONSTRAINT ck_platform_billing_version CHECK (version >= 0),
    CONSTRAINT ck_platform_billing_not_blank CHECK (
        length(btrim(bank_beneficiary)) > 0 AND length(btrim(bank_name)) > 0
        AND length(btrim(bank_account)) > 0 AND length(btrim(bank_mfo)) > 0
        AND length(btrim(bank_tax_id)) > 0
    ),
    -- Real details carry two names, and the same pair as every wallet row (V0211).
    CONSTRAINT ck_platform_billing_four_eyes CHECK (
        NOT configured
        OR (approved_by IS NOT NULL AND approval_request_id IS NOT NULL AND approved_by <> updated_by)
    )
);

COMMENT ON TABLE commercial.platform_billing_settings IS
    'ADR 0095. The one account an invoice tells a tenant to pay into. Starts as a placeholder (configured = false); real details are written only through an approved platform request, so they always name a proposer and a different approver.';

INSERT INTO commercial.platform_billing_settings (
    singleton, bank_beneficiary, bank_name, bank_account, bank_mfo, bank_tax_id,
    configured, version, updated_by, updated_at)
VALUES (
    true,
    '[beneficiary: set by HorecaOS finance]',
    '[bank: set by HorecaOS finance]',
    '[account: set by HorecaOS finance]',
    '[MFO]',
    '[tax id]',
    false, 0, 'migration V0504', now());

GRANT SELECT, UPDATE ON commercial.platform_billing_settings TO horecaos_application;

INSERT INTO audit.approval_policies (
    id, tenant_id, action_code, scope_type, threshold_json, required_approver_capability,
    valid_from, version, approved_by)
VALUES
    ('0192d1a0-0000-7000-8000-000000000504', NULL, 'commercial.billing.bank-details', 'PLATFORM',
     '{"description": "Every change to the bank details an invoice tells a tenant to pay into"}'::jsonb,
     'commercial.wallet.manage', '2026-10-07T00:00:00Z', 1, 'migration V0504');

CREATE TABLE commercial.platform_card_installations (
    id uuid PRIMARY KEY,
    -- Which adapter answers for this account. The set of valid values is the set of adapters the
    -- application has wired, checked at activation, because a database CHECK would have to be
    -- edited for every provider a later wave adds.
    provider_type varchar(64) NOT NULL,
    environment_code varchar(64),
    display_name varchar(255) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    -- A reference, never a value (ADR 0028): rotation changes what is behind it, not this row.
    secret_reference varchar(512),
    external_account_reference varchar(255),
    non_sensitive_config jsonb NOT NULL DEFAULT '{}'::jsonb,
    capability_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    adapter_version varchar(64),
    last_connection_check_at timestamptz,
    last_connection_status varchar(24),
    last_connection_evidence varchar(1000),
    created_by varchar(255) NOT NULL,
    activated_by varchar(255),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_platform_card_installation_environment FOREIGN KEY (environment_code)
        REFERENCES integration.provider_environments (code),
    CONSTRAINT ck_platform_card_installation_status CHECK (
        status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'RETIRED')
    ),
    CONSTRAINT ck_platform_card_installation_connection CHECK (
        last_connection_status IS NULL OR last_connection_status IN ('SUCCEEDED', 'FAILED', 'UNVERIFIED')
    ),
    CONSTRAINT ck_platform_card_installation_config CHECK (jsonb_typeof(non_sensitive_config) = 'object'),
    CONSTRAINT ck_platform_card_installation_capabilities CHECK (jsonb_typeof(capability_snapshot) = 'object'),
    CONSTRAINT ck_platform_card_installation_version CHECK (version >= 0),
    -- ADR 0026: "endpoints come from an approved provider environment catalogue, never from
    -- anything typed". The one provider with no endpoint is the in-process fake.
    CONSTRAINT ck_platform_card_installation_environment CHECK (
        (provider_type = 'FAKE_CARD') = (environment_code IS NULL)
    ),
    CONSTRAINT ck_platform_card_installation_secret CHECK (
        provider_type = 'FAKE_CARD' OR secret_reference IS NOT NULL
    )
);

COMMENT ON TABLE commercial.platform_card_installations IS
    'ADR 0095, ADR 0026. HorecaOS''s OWN card merchant account, in the shape of an ADR 0026 installation (which is tenant-owned and so cannot hold it). Credentials are a secret reference; the endpoint is an approved environment; at most one row is ACTIVE.';

CREATE UNIQUE INDEX ux_platform_card_installation_one_active
    ON commercial.platform_card_installations ((true))
    WHERE status = 'ACTIVE';

GRANT SELECT, INSERT, UPDATE ON commercial.platform_card_installations TO horecaos_application;
