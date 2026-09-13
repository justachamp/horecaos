-- ADR 0109 / Settings 10.11: a tenant-wide consent-type registry.
--
-- `customer.consent_decisions` (V0017) has recorded GRANTED/WITHDRAWN
-- decisions against a free-text `purpose` since the schema was created, and
-- 5.2b's own doc names exactly this gap: "a decision log exists, a type
-- registry does not", so an operator recording consent from the customer
-- screen has to guess a purpose string against whichever of three already
-- differently-spelled values happens to be in use. This table is that
-- registry: one row per purpose a tenant actually asks about, with a label an
-- operator reads instead of typing a code.
--
-- Deliberately tenant-scoped rather than a platform-fixed vocabulary. ADR
-- 0015 leaves the purpose taxonomy to each tenant's own notification and
-- marketing configuration, and a platform-fixed enum would need a release
-- every time a tenant wanted to ask about one more channel. A tenant with no
-- rows yet is not a tenant with no purposes -- see
-- `ConsentTypeService.list`'s own doc for the bootstrap that seeds the
-- well-known defaults on first read, so migrating this schema needs no data
-- migration across tenants that do not exist yet.
CREATE TABLE customer.consent_types (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- Matches `customer.consent_decisions.purpose` exactly: this is the
    -- vocabulary that column's free text is meant to be drawn from.
    code varchar(64) NOT NULL,

    label_ru varchar(200) NOT NULL,
    label_uz varchar(200) NOT NULL,
    label_en varchar(200) NOT NULL,
    description varchar(1000),

    -- Whether a decision under this purpose is asked per channel (SMS,
    -- TELEGRAM, PUSH) or applies uniformly -- mirrors `consent_decisions
    -- .channel`, which is nullable for exactly the purposes that are not.
    channel_specific boolean NOT NULL DEFAULT false,

    -- The legal-evidence version a decision under this purpose currently
    -- cites (`consent_decisions.policy_version`). A tenant that changes its
    -- privacy notice bumps this so new decisions carry the new version
    -- without rewriting history.
    policy_version varchar(32) NOT NULL DEFAULT '1',

    active boolean NOT NULL DEFAULT true,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(255) NOT NULL,
    updated_by varchar(255) NOT NULL,

    CONSTRAINT fk_consent_type_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT ux_consent_type_tenant_code UNIQUE (tenant_id, code)
);

-- The registry read: every purpose a tenant has defined, active first.
CREATE INDEX ix_consent_types_tenant ON customer.consent_types (tenant_id, active, code);

GRANT SELECT, INSERT, UPDATE ON customer.consent_types TO horecaos_application;
