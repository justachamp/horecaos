-- Settings 10.1 + 10.12 (operations-gap-map P32): a brand cannot say which
-- languages its storefront offers, which one is default, or write a
-- description in any of them, so every localized field in the console is
-- authored against a hard-coded ru/uz-Latn/en triple
-- (marketing.AudiencePredicate.SUPPORTED_LOCALES) that no tenant chose.
--
-- One row per locale a brand actually supports, not a fixed set of three
-- nullable columns: a brand that drops a language should not leave a
-- perpetually-empty column behind, the same reasoning V0016's
-- `catalog.translations` already applies one level down (per-product, not
-- per-brand). `description` lives here rather than on `tenant.brands`
-- because 10.1's brand description is itself per-language -- there is no
-- language-less description to fall back to.
CREATE TABLE tenant.brand_locales (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- Matches the frontend's own locale codes exactly (en / ru / uz-Latn) --
    -- see BrandProfile.KNOWN_LOCALES, the closed set the console can author
    -- content in today. Not a foreign key: there is no platform-wide locale
    -- registry yet, and the closed set is enforced in the domain layer so it
    -- can grow without a migration once one exists.
    locale varchar(16) NOT NULL,

    description varchar(2000),

    -- Whether this is the storefront's language when a customer has not
    -- chosen one. Enforced below as at most one per brand; the service layer
    -- requires exactly one whenever the set is non-empty, because a brand
    -- that supports languages and names no default cannot render its own
    -- storefront's first paint.
    is_default boolean NOT NULL DEFAULT false,

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (brand_id, locale),
    CONSTRAINT fk_brand_locales_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id)
);

CREATE UNIQUE INDEX uq_brand_locales_default ON tenant.brand_locales (brand_id) WHERE is_default;

-- The batch read a brand-scoped profile write replaces wholesale.
CREATE INDEX ix_brand_locales_tenant ON tenant.brand_locales (tenant_id, brand_id);

COMMENT ON TABLE tenant.brand_locales IS
    '10.1 / 10.12. A brand''s own supported-language set: which locales its storefront offers, which is default, and each one''s localized description.';

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.brand_locales TO horecaos_application;
