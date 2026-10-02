-- ADR 0138: the channel-scoped media override.
--
-- ADR 0010 named the gap ("per-aggregator image variants") and left it open;
-- ADR 0138 supplies the override mechanism. A row replaces an entity's own
-- images for ONE channel and nothing else: an entity with no row for a channel
-- falls back to what it always showed, so a tenant that never writes a row sees
-- no behaviour change anywhere. Sparse and default-is-the-normal-thing, the same
-- shape as catalog.channel_offering_exclusions (V0020), so an author who has
-- learned one per-channel override screen recognises the other.
--
-- Two deviations from ADR 0138's written physical model, recorded here so that
-- reading the record and reading this file do not produce two different beliefs
-- (V0038 set the precedent for saying so in the migration itself):
--
--   * The primary key carries `media_asset_id`. The record writes the key as
--     (tenant, channel, entity_type, entity_id, role), which allows exactly one
--     asset per role: a channel could override a dish's PRIMARY photo but never
--     its GALLERY, and `sort_order` — which the same record lists — would order
--     nothing. catalog.media_relations (V0016, widened by V0223) keys the same
--     way for the same reason: one PRIMARY and any number of GALLERY.
--     "At most one PRIMARY per entity and channel" is kept, as the partial
--     unique index below, so the record's intent survives.
--   * `brand_id` is a column. Every other catalog table names its brand, the
--     authoring endpoints are brand-scoped (ADR 0025), and a channel is
--     tenant-level — without the column a brand-scoped read would have to join
--     through the entity table to learn whose row this is.
--
-- This table does not replace catalog.media_relations.channel_code (V0223,
-- IA 4.2f), which already stores a per-channel image set that nothing served.
-- The projection layer reads both, this table winning; see ChannelPreviewService.
--
-- entity_id is polymorphic (PRODUCT | VARIANT | CATEGORY) and so carries no
-- foreign key, exactly like catalog.media_relations; the authoring service
-- refuses an entity that is not the caller's brand's.

CREATE TABLE catalog.channel_media_overrides (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    entity_type varchar(16) NOT NULL,
    entity_id uuid NOT NULL,
    role varchar(16) NOT NULL,
    media_asset_id uuid NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, channel_id, entity_type, entity_id, role, media_asset_id),
    CONSTRAINT ck_channel_media_override_entity_type CHECK (
        entity_type IN ('PRODUCT', 'VARIANT', 'CATEGORY')),
    CONSTRAINT ck_channel_media_override_role CHECK (role IN ('PRIMARY', 'GALLERY')),
    CONSTRAINT ck_channel_media_override_sort_order CHECK (sort_order >= 0),
    CONSTRAINT ck_channel_media_override_version CHECK (version >= 1),
    CONSTRAINT fk_channel_media_override_channel FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id),
    CONSTRAINT fk_channel_media_override_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    -- A channel override may only point at an asset of the same tenant. Matching
    -- on (asset_id, tenant_id) is what makes that true at the database rather
    -- than in whichever service happens to write the row (V0058, V0069).
    CONSTRAINT fk_channel_media_override_asset FOREIGN KEY (media_asset_id, tenant_id)
        REFERENCES media.assets (asset_id, tenant_id)
);

-- One PRIMARY per entity and channel; any number of GALLERY.
CREATE UNIQUE INDEX ux_channel_media_override_primary
    ON catalog.channel_media_overrides (tenant_id, channel_id, entity_type, entity_id)
    WHERE role = 'PRIMARY';

-- "Which overrides does this brand have on this channel" is the preview's read.
CREATE INDEX ix_channel_media_override_brand_channel
    ON catalog.channel_media_overrides (tenant_id, brand_id, channel_id);

COMMENT ON TABLE catalog.channel_media_overrides IS
    'ADR 0138 channel-scoped media override: replaces an entity''s own images for one channel only. Sparse; no row means the entity shows its default media on that channel. Wins over catalog.media_relations.channel_code, which wins over the universal ALL relation.';

GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.channel_media_overrides TO horecaos_application;
