-- The support surface a storefront shows: a brand's FAQ and its social links.
--
-- The legacy backend served `/customers/support/faq` and
-- `/customers/support/socials-media` and the platform served neither, so the
-- last two screens of the JizBiz storefront had nowhere to read from.
--
-- Brand-scoped rather than tenant-scoped, because two brands of one tenant
-- routinely have different support channels -- a different Telegram, a
-- different phone, a different set of questions about their own menu. A tenant
-- that wants one FAQ across brands copies it; a tenant forced to share one it
-- cannot differentiate has no way out.
--
-- Text lives in its own table by locale, following catalog.translations, so a
-- third language is rows rather than columns.

CREATE SCHEMA IF NOT EXISTS support;

-- ------------------------------------------------------------------- the FAQ

CREATE TABLE support.faq_categories (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- The operator's stable handle, unique per brand: "DELIVERY", "PAYMENT".
    -- Editors reorder and retranslate; the code is what a support macro or a
    -- deep link can name and keep naming.
    code varchar(64) NOT NULL,

    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_faq_category_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT fk_faq_category_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT uq_faq_category_code UNIQUE (tenant_id, brand_id, code),
    CONSTRAINT uq_faq_category_identity UNIQUE (id, tenant_id, brand_id)
);

CREATE TABLE support.faq_entries (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    category_id uuid NOT NULL,

    code varchar(64) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_faq_entry_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT fk_faq_entry_category FOREIGN KEY (category_id, tenant_id, brand_id)
        REFERENCES support.faq_categories (id, tenant_id, brand_id) ON DELETE CASCADE,
    CONSTRAINT uq_faq_entry_code UNIQUE (tenant_id, brand_id, code),
    CONSTRAINT uq_faq_entry_identity UNIQUE (id, tenant_id, brand_id)
);

CREATE INDEX ix_faq_entries_category ON support.faq_entries (category_id, sort_order);

-- One row per thing per language, following catalog.translations rather than
-- inventing a second shape for the same problem.
--
-- The answer is long-form and the question is not, so they are separate columns
-- with different limits: a 4000-character question is a mistake worth refusing.
CREATE TABLE support.faq_translations (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    entity_type varchar(16) NOT NULL,
    entity_id uuid NOT NULL,
    locale varchar(16) NOT NULL,

    -- A category's name, or an entry's question.
    title varchar(500) NOT NULL,
    -- An entry's answer. Null for a category, which has no body.
    body varchar(4000),

    version integer NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, entity_type, entity_id, locale),
    CONSTRAINT ck_faq_translation_type CHECK (entity_type IN ('CATEGORY', 'ENTRY')),
    CONSTRAINT ck_faq_translation_title CHECK (length(btrim(title)) > 0),
    -- An entry with no answer is a question the storefront would render blank.
    CONSTRAINT ck_faq_translation_body CHECK (
        (entity_type <> 'ENTRY') OR (body IS NOT NULL AND length(btrim(body)) > 0)
    )
);

CREATE INDEX ix_faq_translations_brand ON support.faq_translations (tenant_id, brand_id, locale);

-- ---------------------------------------------------------------- social links

CREATE TABLE support.social_links (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- Which network. A checked vocabulary rather than free text, because the
    -- storefront picks an icon from it and an unknown value renders as nothing.
    platform varchar(32) NOT NULL,

    -- The destination. Held as a URL and not as a handle: a handle needs a
    -- per-platform template to become a link, and the template is the thing
    -- that goes stale.
    url varchar(500) NOT NULL,

    -- Optional override for the icon. Null means the storefront uses its own
    -- artwork for the platform, which is the usual case.
    media_asset_id uuid,

    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_social_platform CHECK (
        platform IN ('TELEGRAM', 'INSTAGRAM', 'FACEBOOK', 'YOUTUBE', 'TIKTOK',
                     'WEBSITE', 'PHONE', 'EMAIL')
    ),
    CONSTRAINT ck_social_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    -- Only http(s), tel: and mailto:. A javascript: or data: URL authored here
    -- would be rendered as a link by every storefront that reads this table.
    CONSTRAINT ck_social_url CHECK (url ~* '^(https?://|tel:|mailto:)'),
    CONSTRAINT fk_social_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT uq_social_identity UNIQUE (id, tenant_id, brand_id),
    -- One live link per network per brand. Two Telegrams is an editing mistake
    -- that reaches customers as two buttons that look identical.
    CONSTRAINT uq_social_platform UNIQUE (tenant_id, brand_id, platform)
);

CREATE INDEX ix_social_links_brand
    ON support.social_links (tenant_id, brand_id, sort_order)
    WHERE status = 'PUBLISHED';

-- ---------------------------------------------------------------------- grants
--
-- Explicit, and in this migration: GRANT ... ON ALL TABLES IN SCHEMA covers only
-- what existed when it ran, so no earlier grant reaches these.

GRANT USAGE ON SCHEMA support TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON support.faq_categories TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON support.faq_entries TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON support.faq_translations TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON support.social_links TO qoida_application;
