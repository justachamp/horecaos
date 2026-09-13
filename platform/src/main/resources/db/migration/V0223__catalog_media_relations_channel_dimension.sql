-- ADR 0016/0010, wave P22 (IA 4.2f): per-aggregator image overrides.
--
-- `catalog.media_relations` (V0016) has always had exactly one row per
-- (entity_type, entity_id, media_asset_id, role) — one PRIMARY photo and any
-- number of GALLERY photos, the same picture on every channel. IA 4.2f asks
-- for more: an operator gives one aggregator a different crop of the same
-- dish without touching what the storefront or any other channel shows. That
-- needs a second axis this table never had.
--
-- 'ALL' is the sentinel for "every channel that has no override" — the
-- universal image every relation has carried until now — and any other value
-- is a tenant's own `tenant.sales_channels.code` (ADR 0036). Free text and
-- unconstrained by a foreign key, exactly like `catalog.publications.channel`
-- (V0016) and `catalog.channel_offering_exclusions.channel_code` (V0020):
-- a channel is registered per tenant, and this column follows the same
-- convention those two already established rather than inventing a second one.
--
-- What this migration does not do: nothing yet reads `channel_code` when a
-- publication snapshot or a storefront/aggregator projection is built —
-- `CatalogSnapshotLoader.buildSnapshot` still walks every relation for
-- validation regardless of channel, which is correct for "is this asset
-- displayable" and blind to "which image does channel X get". Serving the
-- override at read time is the channel projection layer IA 4.6a describes,
-- not this table, and is out of scope for this wave.
ALTER TABLE catalog.media_relations
    ADD COLUMN channel_code varchar(32) NOT NULL DEFAULT 'ALL';

-- Widen the key rather than add a second unique constraint: two relations
-- naming the same (entity, asset, role) and two different channels are two
-- different facts now, not a conflict. Existing rows keep their default 'ALL'
-- and are unaffected — this can only relax uniqueness, so no row can violate
-- the new key that did not already violate the old one.
ALTER TABLE catalog.media_relations
    DROP CONSTRAINT media_relations_pkey;

ALTER TABLE catalog.media_relations
    ADD CONSTRAINT media_relations_pkey
        PRIMARY KEY (entity_type, entity_id, media_asset_id, role, channel_code);

COMMENT ON COLUMN catalog.media_relations.channel_code IS
    'ADR 0016/0036, wave P22 (IA 4.2f). ALL is the universal image every channel falls back to absent an override; any other value is a tenant.sales_channels.code (free text, unenforced by a foreign key — same convention as catalog.publications.channel) whose row overrides the universal image for that channel alone. Not yet read by CatalogSnapshotLoader or any projection: this column stores the override, serving it is IA 4.6a''s channel projection layer.';
