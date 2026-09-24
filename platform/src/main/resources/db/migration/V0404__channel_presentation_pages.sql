-- ADR 0036, row 10.5's WEB/AGGREGATOR "presentation" facet: SEO meta and the
-- storefront's own static pages (about, contacts, delivery terms,
-- privacy/offer).
--
-- Two different shapes for two different write disciplines, matching the
-- split this codebase already makes elsewhere:
--
-- channel_presentation is mutable, single-row-per-channel state -- exactly
-- like tenant.sales_channels' own icon/brand-colour columns (V0385): there is
-- one current SEO title, one current description, one current OG image, and
-- correcting a typo does not need a history.
--
-- channel_pages/channel_page_contents is append-only and versioned, one
-- locale per row, copying legal.terms_versions/terms_version_contents
-- (V0160) field for field and reason for reason: a customer may be reading a
-- delivery-terms page right now, and this wave has no acceptance record
-- pointing at a specific version the way legal.terms does, but the same
-- argument against silently rewriting published text under a live URL
-- applies -- a page a customer already saw should not retroactively read as
-- something else. Publishing therefore inserts the next version rather than
-- updating the current one; the storefront always renders the highest
-- version.

-- ------------------------------------------------------------- presentation

CREATE TABLE tenant.channel_presentation (
    tenant_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    seo_title varchar(200),
    seo_description varchar(500),
    -- media.media_assets.id (ADR 0010). Not a foreign key: media lives in its
    -- own module/schema and tenancy takes no dependency on it, the same
    -- convention catalog.media_relations.media_asset_id already follows.
    -- StorefrontMediaController already answers not-found for an asset id
    -- that does not exist, is not PUBLIC, or is not this tenant's, so a
    -- dangling reference here fails the same closed way at render time.
    og_image_asset_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, channel_id),
    CONSTRAINT fk_channel_presentation_channel FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id) ON DELETE CASCADE
);

COMMENT ON TABLE tenant.channel_presentation IS
    'ADR 0036 row 10.5: a channel''s SEO title/description and OG image. One row per channel, corrected in place -- unlike channel_pages below, nothing here is evidence a customer read.';

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.channel_presentation TO horecaos_application;

-- ------------------------------------------------------------------- pages

CREATE TABLE tenant.channel_pages (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    channel_id uuid NOT NULL,

    -- The closed set row 10.5 names: about, contacts, delivery terms,
    -- privacy/offer. Code-owned (tenancy.domain.channel.ChannelPageSlug) for
    -- the same reason every other closed vocabulary in this schema is --
    -- free text here would let the storefront's /pages/{slug} route be asked
    -- for a slug nothing renders a link to.
    slug varchar(32) NOT NULL,

    -- Monotonic per (tenant_id, channel_id, slug), starting at 1. Never
    -- reused -- uq_channel_page_version below is what a concurrent publish
    -- collides against, the same shape legal.terms_versions' own
    -- uq_terms_version gives ApprovalPolicyService.author and
    -- TermsPublishingService.publish.
    version integer NOT NULL,

    published_by varchar(255) NOT NULL,
    published_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_channel_page_version_positive CHECK (version > 0),
    CONSTRAINT ck_channel_page_slug CHECK (
        slug IN ('about', 'contacts', 'delivery-terms', 'privacy-offer')),
    CONSTRAINT fk_channel_page_channel FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_channel_page_version UNIQUE (tenant_id, channel_id, slug, version),
    -- Referenced by channel_page_contents below so its own foreign key can
    -- carry tenant_id, matching legal.terms_versions' own
    -- uq_terms_versions_id_tenant pattern rather than trusting a bare id to
    -- never cross a tenant boundary.
    CONSTRAINT uq_channel_pages_id_tenant UNIQUE (id, tenant_id)
);

CREATE INDEX ix_channel_pages_current ON tenant.channel_pages (tenant_id, channel_id, slug, version DESC);

CREATE TABLE tenant.channel_page_contents (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    channel_page_id uuid NOT NULL,

    -- The platform's closed three-locale set, matching
    -- legal.terms_version_contents.locale (V0160) and
    -- notifications.domain.MessageLocale's own ru/uz-Latn/en tags. Not
    -- required to cover all three -- a version with only Russian text is a
    -- legitimate row, and the storefront falls back to "page not available
    -- in this language" for a locale absent here, never to another
    -- language's text.
    locale varchar(16) NOT NULL,
    -- "Markdown-ish": plain text with blank-line paragraph breaks, never raw
    -- HTML. The storefront escapes it and turns blank lines into paragraph
    -- breaks (see storefront's page-content pipe) rather than interpreting
    -- markup -- settings.md 10.5's own WEB section calls out tenant-authored
    -- raw HTML as an XSS surface pointed at the tenant's own customers, and
    -- this column's contract is the same refusal by construction rather
    -- than by a sanitizer that has to keep up with every new tag.
    body text NOT NULL,

    CONSTRAINT ck_channel_page_content_locale CHECK (locale IN ('ru', 'uz-Latn', 'en')),
    CONSTRAINT ck_channel_page_content_body_not_blank CHECK (btrim(body) <> ''),
    CONSTRAINT fk_channel_page_content_page FOREIGN KEY (channel_page_id, tenant_id)
        REFERENCES tenant.channel_pages (id, tenant_id),
    CONSTRAINT uq_channel_page_content_locale UNIQUE (channel_page_id, locale)
);

CREATE INDEX ix_channel_page_contents_page ON tenant.channel_page_contents (channel_page_id);

COMMENT ON TABLE tenant.channel_pages IS
    'ADR 0036 row 10.5: a channel''s static pages (about/contacts/delivery-terms/privacy-offer), versioned by insert like legal.terms_versions. The storefront always renders the highest version.';

-- Insert and read only. A page a customer may already have read is evidence
-- of what the storefront showed them, the same reasoning legal.terms_versions
-- (V0160) withholds UPDATE/DELETE for.
GRANT SELECT, INSERT ON tenant.channel_pages TO horecaos_application;
GRANT SELECT, INSERT ON tenant.channel_page_contents TO horecaos_application;
