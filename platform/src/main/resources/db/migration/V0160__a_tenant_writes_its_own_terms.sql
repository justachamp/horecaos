-- A tenant's own terms of service: versioned, authored per locale in the
-- tenant's own operations console (ADR 0068).
--
-- The storefront's terms-of-service copy was hardcoded and named the legacy
-- brand this codebase was imported from. Every tenant onboarded since shipped
-- another company's legal text to their own customers, because a prior wave
-- made brand identity configurable but deliberately left legal text alone --
-- it is not a find-and-replace.
--
-- Versioned by insert, never by update: a customer accepted specific words at
-- a specific time (customer.consent_decisions, ADR 0015), and rewriting that
-- text under an acceptance already on record would make the acceptance
-- evidence of nothing. Publishing is therefore append-only at the database
-- level too -- the GRANT below withholds UPDATE and DELETE the same way
-- V0017's consent_decisions and V0007's audit_events do.
--
-- Brand-scoped, following support.faq_categories (V0094): a tenant may run
-- several brands, each its own storefront deployment (AppConfig.brandId) and
-- routinely its own legal seller, and a tenant that wants one document across
-- brands publishes it once per brand.
--
-- Content lives in its own table by locale, the same split V0094 made for the
-- FAQ, so a third language is a row rather than a column and a version with
-- only two authored languages is a normal, valid row rather than a null-filled
-- one.

CREATE SCHEMA IF NOT EXISTS legal;

CREATE TABLE legal.terms_versions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- Monotonic per (tenant_id, brand_id), starting at 1. Never reused: the
    -- uq_terms_version constraint below is what a concurrent publish collides
    -- against, the same shape ApprovalPolicyService.author relies on for
    -- audit.approval_policies.
    version integer NOT NULL,

    published_by varchar(255) NOT NULL,
    published_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_terms_version_positive CHECK (version > 0),
    CONSTRAINT fk_terms_version_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT uq_terms_version UNIQUE (tenant_id, brand_id, version),
    -- Referenced by terms_version_contents below so its own foreign key can
    -- carry tenant_id, matching this table's own composite pattern rather
    -- than trusting a bare id to never cross a tenant boundary.
    CONSTRAINT uq_terms_versions_id_tenant UNIQUE (id, tenant_id)
);

CREATE INDEX ix_terms_versions_current ON legal.terms_versions (tenant_id, brand_id, version DESC);

CREATE TABLE legal.terms_version_contents (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    terms_version_id uuid NOT NULL,

    -- The platform's closed three-locale set (legal.domain.TermsLocale),
    -- matching notifications.domain.MessageLocale's own ru/uz-Latn/en tags.
    -- Not required to cover all three: a version with only Uzbek and Russian
    -- text is legitimate, and the English-reading customer sees the platform
    -- default for that language until the tenant writes their own.
    locale varchar(16) NOT NULL,
    body text NOT NULL,

    CONSTRAINT ck_terms_content_locale CHECK (locale IN ('ru', 'uz-Latn', 'en')),
    CONSTRAINT ck_terms_content_body_not_blank CHECK (btrim(body) <> ''),
    CONSTRAINT fk_terms_content_version FOREIGN KEY (terms_version_id, tenant_id)
        REFERENCES legal.terms_versions (id, tenant_id),
    CONSTRAINT uq_terms_content_locale UNIQUE (terms_version_id, locale)
);

CREATE INDEX ix_terms_contents_version ON legal.terms_version_contents (terms_version_id);

GRANT USAGE ON SCHEMA legal TO horecaos_application;

-- Insert and read only, on both tables. A published version and its content
-- are evidence of what a customer was shown and may have accepted; a role
-- that could UPDATE or DELETE either could rewrite history a consent
-- decision already points at.
GRANT SELECT, INSERT ON legal.terms_versions TO horecaos_application;
GRANT SELECT, INSERT ON legal.terms_version_contents TO horecaos_application;
