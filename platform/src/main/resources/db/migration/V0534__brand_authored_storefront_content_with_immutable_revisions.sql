-- ADR 0157: the `content` module's tables -- brand-authored storefront content (banners,
-- promo cards, home product groups, stories, pop-ups) and, behind a per-brand switch, the
-- editorial tier (news, gallery albums, recipes, a vacancy listing).
--
-- One item model for every kind. An item has at most ONE mutable draft and any number of
-- IMMUTABLE revisions (insert-only by grant, not by convention): "what a customer may have been
-- shown" is evidence in a disputed promotion claim, and an UPDATE grant on the revision table
-- would make it a sentence in a comment. Publishing writes the draft as the next revision and
-- points the item at it; withdrawing clears the pointer and keeps every revision.
--
-- "Scheduled", "live" and "expired" are NEVER stored. They are computed from the live
-- revision's starts_at and ends_at at the moment of every read, so no sweeper exists that can
-- fail and leave a banner up past its end.
--
-- Tenant scope: every row carries a non-null tenant_id and every foreign key names it, so a
-- row can never point at another tenant's brand, asset, promotion or branch. Row-level
-- security (ADR 0056) is enrolled schema by schema; `content` joins when that rollout reaches
-- it, and every query carries an explicit tenant predicate until then.
--
-- The text a tenant wrote lives in `document` (typed jsonb, schemaVersion 1, unknown keys
-- refused by the server on write). Nothing queries inside it; what must be queried or
-- referentially checked is lifted into columns: the window, the channel set, the promotion, the
-- vacancy branch and, through revision_media, every asset.

CREATE SCHEMA IF NOT EXISTS content;
GRANT USAGE ON SCHEMA content TO horecaos_application;

-- ---------------------------------------------------------------------- items

CREATE TABLE content.items (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    kind varchar(16) NOT NULL,
    -- Null for the editorial kinds, which are not placed on the home screen.
    placement varchar(16),

    -- Staff-facing, never sent to a customer.
    internal_name varchar(120) NOT NULL,
    -- The brand-default-locale title, for lists. Kept in step with the draft by the service.
    display_title varchar(200) NOT NULL DEFAULT '',
    -- Editorial kinds only.
    slug varchar(80),

    state varchar(10) NOT NULL DEFAULT 'DRAFT',
    -- Set exactly when state = PUBLISHED.
    live_revision_no integer,

    -- Order within a placement. Changed by an audited reorder that takes effect at once and is
    -- not a revision.
    sort_key integer NOT NULL DEFAULT 0,
    -- ADR 0031 optimistic concurrency; the ETag of every staff read.
    version integer NOT NULL DEFAULT 1,

    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_content_item_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT uq_content_item_identity UNIQUE (id, tenant_id, brand_id),
    CONSTRAINT ck_content_item_kind CHECK (kind IN (
        'BANNER', 'PROMO_CARD', 'PRODUCT_GROUP', 'STORY_GROUP', 'POPUP',
        'NEWS', 'GALLERY_ALBUM', 'RECIPE', 'VACANCY')),
    CONSTRAINT ck_content_item_placement CHECK (placement IS NULL OR placement IN (
        'HOME_CAROUSEL', 'HOME_GROUPS', 'STORY_RAIL', 'PROMO_GRID', 'ENTRY_MODAL')),
    -- The pairs ADR 0157 allows, and nothing else: a banner cannot sit in the story rail.
    CONSTRAINT ck_content_item_pair CHECK (
        (kind = 'BANNER' AND placement = 'HOME_CAROUSEL')
        OR (kind = 'PROMO_CARD' AND placement IN ('HOME_CAROUSEL', 'PROMO_GRID'))
        OR (kind = 'PRODUCT_GROUP' AND placement = 'HOME_GROUPS')
        OR (kind = 'STORY_GROUP' AND placement = 'STORY_RAIL')
        OR (kind = 'POPUP' AND placement = 'ENTRY_MODAL')
        OR (kind IN ('NEWS', 'GALLERY_ALBUM', 'RECIPE', 'VACANCY') AND placement IS NULL)),
    CONSTRAINT ck_content_item_state CHECK (state IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN', 'ARCHIVED')),
    CONSTRAINT ck_content_item_live CHECK ((state = 'PUBLISHED') = (live_revision_no IS NOT NULL)),
    CONSTRAINT ck_content_item_slug_format CHECK (slug IS NULL OR slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    -- An editorial item is addressed by a slug; a merchandising item has none.
    CONSTRAINT ck_content_item_slug_kind CHECK (
        (kind IN ('NEWS', 'GALLERY_ALBUM', 'RECIPE', 'VACANCY')) = (slug IS NOT NULL)),
    CONSTRAINT ck_content_item_version CHECK (version >= 1),
    CONSTRAINT ck_content_item_name CHECK (btrim(internal_name) <> '')
);

-- A slug names one live-or-draft item of a kind within a brand; an archived item gives its slug back.
CREATE UNIQUE INDEX ux_content_item_slug
    ON content.items (tenant_id, brand_id, kind, slug)
    WHERE slug IS NOT NULL AND state <> 'ARCHIVED';

-- The storefront read: the brand's live items of a placement, in order.
CREATE INDEX ix_content_items_published
    ON content.items (tenant_id, brand_id, placement, sort_key)
    WHERE state = 'PUBLISHED';

-- The console list.
CREATE INDEX ix_content_items_brand
    ON content.items (tenant_id, brand_id, placement, sort_key, created_at, id)
    WHERE state <> 'ARCHIVED';

COMMENT ON TABLE content.items IS
    'ADR 0157: identity, lifecycle and order of one piece of brand-authored storefront content. No DELETE grant: an item is archived, never removed, because its revisions are evidence of what customers were shown. live/scheduled/expired are computed on read from the live revision''s window and are never stored.';

-- --------------------------------------------------------------------- drafts

CREATE TABLE content.item_drafts (
    item_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- The revision this draft was cloned from by a republication; null for a fresh draft.
    based_on_revision_no integer,

    document jsonb NOT NULL,
    -- Empty means every content-capable channel (and is NOT the same as a list of every channel).
    channel_codes varchar(32)[] NOT NULL DEFAULT '{}',
    starts_at timestamptz,
    ends_at timestamptz,
    -- A promo card bound to a promotion is visible only while that promotion is live.
    promotion_id uuid,
    -- A vacancy's branch.
    location_id uuid,

    updated_by varchar(255) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    version integer NOT NULL DEFAULT 1,

    CONSTRAINT fk_content_draft_item FOREIGN KEY (item_id, tenant_id, brand_id)
        REFERENCES content.items (id, tenant_id, brand_id),
    CONSTRAINT fk_content_draft_promotion FOREIGN KEY (promotion_id, tenant_id, brand_id)
        REFERENCES pricing.promotions (id, tenant_id, brand_id),
    CONSTRAINT fk_content_draft_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT ck_content_draft_window CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_content_draft_size CHECK (octet_length(document::text) <= 262144)
);

COMMENT ON TABLE content.item_drafts IS
    'ADR 0157: the one mutable copy of an item. Carries the six values a revision''s content_hash covers (document, channel_codes, starts_at, ends_at, promotion_id, location_id). A draft has no hash column: it is compared only at the moment it is published.';

-- ------------------------------------------------------------------ revisions

CREATE TABLE content.item_revisions (
    item_id uuid NOT NULL,
    revision_no integer NOT NULL,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    document jsonb NOT NULL,
    channel_codes varchar(32)[] NOT NULL DEFAULT '{}',
    starts_at timestamptz,
    ends_at timestamptz,
    promotion_id uuid,
    location_id uuid,

    -- SHA-256 (hex) over the canonical serialisation of the six values above. Excludes the
    -- revision number, the author and the publication time, which differ on every publish by
    -- construction. Computed once, by the publish service.
    content_hash char(64) NOT NULL,

    published_by varchar(255) NOT NULL,
    published_at timestamptz NOT NULL,

    CONSTRAINT pk_content_revision PRIMARY KEY (item_id, revision_no),
    -- The tenant-scoped key revision_media and item_daily_stats reference.
    CONSTRAINT uq_content_revision_tenant UNIQUE (item_id, revision_no, tenant_id),
    CONSTRAINT fk_content_revision_item FOREIGN KEY (item_id, tenant_id, brand_id)
        REFERENCES content.items (id, tenant_id, brand_id),
    CONSTRAINT fk_content_revision_promotion FOREIGN KEY (promotion_id, tenant_id, brand_id)
        REFERENCES pricing.promotions (id, tenant_id, brand_id),
    CONSTRAINT fk_content_revision_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT ck_content_revision_number CHECK (revision_no >= 1),
    CONSTRAINT ck_content_revision_window CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_content_revision_size CHECK (octet_length(document::text) <= 262144),
    CONSTRAINT ck_content_revision_hash CHECK (content_hash ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE content.item_revisions IS
    'ADR 0157: insert-only by grant. What a customer may have been shown. No UPDATE, no DELETE.';

-- The live pointer is a real foreign key, now that the revisions table exists. Nullable and
-- MATCH SIMPLE: a draft or withdrawn item points at nothing.
ALTER TABLE content.items
    ADD CONSTRAINT fk_content_item_live_revision FOREIGN KEY (id, live_revision_no, tenant_id)
        REFERENCES content.item_revisions (item_id, revision_no, tenant_id);

-- --------------------------------------------------------------- revision media

CREATE TABLE content.revision_media (
    item_id uuid NOT NULL,
    revision_no integer NOT NULL,
    tenant_id uuid NOT NULL,
    media_asset_id uuid NOT NULL,
    role varchar(16) NOT NULL,
    position integer NOT NULL DEFAULT 0,

    CONSTRAINT pk_content_revision_media PRIMARY KEY (item_id, revision_no, media_asset_id, role),
    CONSTRAINT fk_content_revision_media_revision FOREIGN KEY (item_id, revision_no, tenant_id)
        REFERENCES content.item_revisions (item_id, revision_no, tenant_id),
    -- A deleted asset's row stays behind (DELETED), which satisfies this key and cannot be shown.
    CONSTRAINT fk_content_revision_media_asset FOREIGN KEY (media_asset_id, tenant_id)
        REFERENCES media.assets (asset_id, tenant_id),
    CONSTRAINT ck_content_revision_media_role CHECK (
        role IN ('PRIMARY', 'POSTER', 'SLIDE', 'AVATAR', 'BODY_IMAGE', 'ALBUM_IMAGE'))
);

CREATE INDEX ix_content_revision_media_asset ON content.revision_media (tenant_id, media_asset_id);

COMMENT ON TABLE content.revision_media IS
    'ADR 0157: which asset each revision uses, one row per use. The relation the retention sweeper and the media usage probe read: an asset a live revision uses is never released.';

-- ------------------------------------------------------------- allowed hosts

CREATE TABLE content.allowed_hosts (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    host varchar(253) NOT NULL,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_content_allowed_host PRIMARY KEY (tenant_id, brand_id, host),
    CONSTRAINT fk_content_allowed_host_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    -- Lowercase DNS labels, at least two: no wildcard, no port, no userinfo, no scheme.
    CONSTRAINT ck_content_allowed_host_format CHECK (
        host ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$')
);

COMMENT ON TABLE content.allowed_hosts IS
    'ADR 0157: the external-link allowlist, per brand. The open-redirect control: an EXTERNAL link or a block href is accepted only if its host is here or is one of the tenant''s verified channel hostnames, at publish and again on every read. Each addition and removal is an audited security fact.';

-- --------------------------------------------------------------------- grants

-- An item is archived, never deleted.
GRANT SELECT, INSERT, UPDATE ON content.items TO horecaos_application;
-- The one mutable copy; a discarded draft is deleted.
GRANT SELECT, INSERT, UPDATE, DELETE ON content.item_drafts TO horecaos_application;
-- Immutability by grant: no UPDATE, no DELETE.
GRANT SELECT, INSERT ON content.item_revisions TO horecaos_application;
GRANT SELECT, INSERT ON content.revision_media TO horecaos_application;
GRANT SELECT, INSERT, DELETE ON content.allowed_hosts TO horecaos_application;
