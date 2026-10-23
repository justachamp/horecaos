-- ADR 0187 (D25): a KIOSK channel has idle media -- up to eight 9:16 pictures of at most 1 MB, in a chosen order --
-- that a paired kiosk's shell plays on its status screen.
--
-- 1. media.assets gains a PURPOSE. ADR 0010 predicted the column ("idle media needs a purpose column"): until now
--    nothing said what an asset was FOR, so a catalogue photograph uploaded for a menu could be attached to a lobby
--    screen. A purposed asset is a PUBLIC, TENANT-owned image (the kiosk's configuration lists it by the anonymous
--    storefront media route, which serves a PUBLIC displayable asset and nothing else), and the database says so.
--    Null for every asset that exists, so nothing is rewritten.
--
-- 2. tenant.kiosk_idle_media_sets is the channel's list and the version the list's If-Match is checked against; the
--    rows in tenant.kiosk_idle_media are its items. The set references the channel through the (tenant_id, id,
--    system_type) key V0526 added, so a WEB channel cannot have a set; every key carries tenant_id.
--
-- 3. At most eight per channel is a CHECK on the position (0..7) plus a unique key on it, so the ninth row cannot
--    exist whatever the service does. The unique key is DEFERRABLE INITIALLY DEFERRED so a reorder is one statement
--    inside one transaction (two rows swap positions without a moment where both hold the same one).
--
-- Idle media is configuration, not history: a removed picture is removed (the audit fact is the record), so the
-- application role may delete from the items table and from nothing else here.

ALTER TABLE media.assets
    ADD COLUMN purpose varchar(16),
    ADD CONSTRAINT ck_media_asset_purpose CHECK (purpose IS NULL OR purpose = 'KIOSK_IDLE'),
    ADD CONSTRAINT ck_media_asset_purpose_shape CHECK (
        purpose IS NULL OR (owner_scope = 'TENANT' AND visibility = 'PUBLIC'));

COMMENT ON COLUMN media.assets.purpose IS
    'ADR 0187 (D25): what the asset is for. Null for every asset uploaded for a menu, a brand or a person. KIOSK_IDLE is a picture on a kiosk channel''s idle loop: always a PUBLIC, TENANT-owned image, verified at 9:16 and at most 1 MB.';

CREATE TABLE tenant.kiosk_idle_media_sets (
    tenant_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    -- Always 'KIOSK': a column so that a foreign key can carry it into the channel table's
    -- (tenant_id, id, system_type) key, as tenant.kiosk_devices does (V0526).
    channel_type varchar(16) NOT NULL DEFAULT 'KIOSK',
    version integer NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_kiosk_idle_media_sets PRIMARY KEY (tenant_id, channel_id),
    CONSTRAINT ck_kiosk_idle_media_set_type CHECK (channel_type = 'KIOSK'),
    CONSTRAINT ck_kiosk_idle_media_set_version CHECK (version >= 1),
    CONSTRAINT fk_kiosk_idle_media_set_channel FOREIGN KEY (tenant_id, channel_id, channel_type)
        REFERENCES tenant.sales_channels (tenant_id, id, system_type)
);

CREATE TABLE tenant.kiosk_idle_media (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    channel_id uuid NOT NULL,
    asset_id uuid NOT NULL,
    -- 0..7: at most eight pictures on a channel, enforced here and not only by the service.
    position smallint NOT NULL,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_kiosk_idle_media PRIMARY KEY (id),
    CONSTRAINT uq_kiosk_idle_media_identity UNIQUE (tenant_id, id),
    CONSTRAINT ck_kiosk_idle_media_position CHECK (position BETWEEN 0 AND 7),
    CONSTRAINT fk_kiosk_idle_media_set FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.kiosk_idle_media_sets (tenant_id, channel_id),
    CONSTRAINT fk_kiosk_idle_media_asset FOREIGN KEY (asset_id, tenant_id)
        REFERENCES media.assets (asset_id, tenant_id),
    -- A picture is on a channel once.
    CONSTRAINT uq_kiosk_idle_media_asset UNIQUE (tenant_id, channel_id, asset_id),
    CONSTRAINT uq_kiosk_idle_media_position UNIQUE (tenant_id, channel_id, position)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX ix_kiosk_idle_media_asset ON tenant.kiosk_idle_media (tenant_id, asset_id);

COMMENT ON TABLE tenant.kiosk_idle_media IS
    'ADR 0187. The pictures a KIOSK channel''s paired kiosks play on their status screen, in position order. At most eight; 9:16; at most 1 MB; checked when attached and again by the media pipeline.';

GRANT SELECT, INSERT, UPDATE ON tenant.kiosk_idle_media_sets TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.kiosk_idle_media TO horecaos_application;
