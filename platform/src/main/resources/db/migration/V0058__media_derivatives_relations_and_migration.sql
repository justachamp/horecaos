-- ADR 0010: derivative, relation-integrity and legacy-migration tables.

-- A tenant-scoped unique key on the asset, so every reference to an asset can
-- carry the tenant and be checked by the database rather than by whoever wrote
-- the query. media.assets is keyed on asset_id alone, and a single-column
-- reference lets one tenant's row point at another tenant's asset.
ALTER TABLE media.assets
    ADD CONSTRAINT uq_media_assets_tenant_scoped UNIQUE (asset_id, tenant_id);

-- Where the file came from, for the dual-read fallback window. ADR 0010's
-- physical model names it; V0015 did not create it, so an asset copied from the
-- legacy filesystem currently keeps no record of its origin.
ALTER TABLE media.assets
    ADD COLUMN legacy_path varchar(2048);

COMMENT ON COLUMN media.assets.legacy_path IS
    'The legacy filesystem path this asset was copied from. Retained through the rollback window; never used to build a URL.';

CREATE TABLE media.derivatives (
    derivative_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    asset_id uuid NOT NULL,
    -- The variant code is part of the object key and never changes once written.
    variant varchar(32) NOT NULL,
    object_key varchar(512) NOT NULL,
    bucket varchar(128) NOT NULL,
    content_type varchar(128) NOT NULL,
    size_bytes bigint NOT NULL,
    checksum_sha256 varchar(64) NOT NULL,
    width_px integer NOT NULL,
    height_px integer NOT NULL,
    -- Which renderer produced these bytes. Without it, a change of encoder is
    -- indistinguishable from a corrupt file when somebody asks why one thumbnail
    -- looks different from its neighbours, and a re-render sweep has nothing to
    -- select on.
    processor_version varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- One row per rendition. This is what makes at-least-once delivery of the
    -- availability event safe: two workers racing produce one row, not two rows
    -- both claiming to be the thumbnail.
    CONSTRAINT uq_media_derivative UNIQUE (asset_id, variant),
    CONSTRAINT ck_media_derivative_variant CHECK (variant IN ('THUMBNAIL', 'CARD', 'DETAIL')),
    CONSTRAINT ck_media_derivative_size CHECK (size_bytes > 0),
    CONSTRAINT ck_media_derivative_dimensions CHECK (width_px > 0 AND height_px > 0),
    -- Composite, so a derivative cannot be hung on another tenant's asset.
    CONSTRAINT fk_media_derivative_asset FOREIGN KEY (asset_id, tenant_id)
        REFERENCES media.assets (asset_id, tenant_id)
);
CREATE UNIQUE INDEX ux_media_derivatives_key ON media.derivatives (bucket, object_key);
CREATE INDEX ix_media_derivatives_asset ON media.derivatives (tenant_id, asset_id);
GRANT SELECT, INSERT, UPDATE, DELETE ON media.derivatives TO qoida_application;

CREATE TABLE media.migration_runs (
    run_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    -- Which legacy volume or host the inventory was taken from. Two runs over
    -- two sources must not be mistaken for a retry of one.
    source_label varchar(128) NOT NULL,
    phase varchar(32) NOT NULL,
    status varchar(24) NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    created_by uuid,
    notes varchar(1000),
    CONSTRAINT ck_media_migration_phase CHECK (phase IN (
        'INVENTORY', 'CLASSIFIED', 'APPROVED', 'COPYING', 'VERIFYING',
        'RECONCILED', 'CUTOVER', 'ROLLED_BACK')),
    CONSTRAINT ck_media_migration_status CHECK (status IN (
        'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED')),
    -- Referenced by items together with the tenant, so an item cannot join a
    -- run belonging to somebody else.
    CONSTRAINT uq_media_migration_run_tenant UNIQUE (run_id, tenant_id)
);
GRANT SELECT, INSERT, UPDATE ON media.migration_runs TO qoida_application;

-- Approved legacy-prefix to owner mappings. Globally unique on the prefix,
-- because a directory belongs to exactly one tenant; two tenants claiming the
-- same prefix is the ambiguity phase 3 exists to resolve, not a state to store.
CREATE TABLE media.legacy_path_mappings (
    mapping_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    legacy_prefix varchar(1024) NOT NULL,
    owner_scope varchar(32) NOT NULL,
    owner_id uuid NOT NULL,
    approved_by uuid NOT NULL,
    approved_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_legacy_prefix UNIQUE (legacy_prefix),
    CONSTRAINT ck_legacy_mapping_scope CHECK (owner_scope IN ('TENANT', 'BRAND', 'LOCATION'))
);
GRANT SELECT, INSERT, UPDATE ON media.legacy_path_mappings TO qoida_application;

CREATE TABLE media.migration_items (
    item_id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    -- As found on disk, and after normalization. Both, because the classifier's
    -- verdict is only auditable next to the path it was given.
    source_path varchar(2048) NOT NULL,
    normalized_path varchar(2048) NOT NULL,
    classification varchar(24) NOT NULL,
    owner_scope varchar(32),
    owner_id uuid,
    source_size_bytes bigint,
    source_checksum_sha256 varchar(64),
    asset_id uuid,
    target_object_key varchar(512),
    copy_status varchar(24) NOT NULL DEFAULT 'PENDING',
    destination_size_bytes bigint,
    destination_checksum_sha256 varchar(64),
    attempts integer NOT NULL DEFAULT 0,
    last_error varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    copied_at timestamptz,
    verified_at timestamptz,
    -- Source identity within a run. This is what makes an interrupted copy a
    -- retry rather than a second copy.
    CONSTRAINT uq_media_migration_item UNIQUE (run_id, normalized_path),
    CONSTRAINT ck_media_migration_classification CHECK (classification IN (
        'MAPPED', 'UNMAPPED', 'MISSING', 'ORPHANED', 'DUPLICATE', 'UNSAFE', 'AMBIGUOUS')),
    CONSTRAINT ck_media_migration_copy_status CHECK (copy_status IN (
        'PENDING', 'COPIED', 'VERIFIED', 'FAILED', 'SKIPPED')),
    -- A verified copy has evidence on both sides. Without this, "verified" is a
    -- word rather than a comparison.
    CONSTRAINT ck_media_migration_verified CHECK (
        copy_status <> 'VERIFIED' OR (
            source_checksum_sha256 IS NOT NULL
            AND destination_checksum_sha256 IS NOT NULL
            AND source_size_bytes IS NOT NULL
            AND destination_size_bytes IS NOT NULL)),
    CONSTRAINT fk_media_migration_item_run FOREIGN KEY (run_id, tenant_id)
        REFERENCES media.migration_runs (run_id, tenant_id),
    CONSTRAINT fk_media_migration_item_asset FOREIGN KEY (asset_id, tenant_id)
        REFERENCES media.assets (asset_id, tenant_id)
);
-- Drives the resumable copy: claim the next batch of unfinished work in a run.
CREATE INDEX ix_media_migration_items_pending
    ON media.migration_items (run_id, copy_status)
    WHERE copy_status IN ('PENDING', 'FAILED');
-- Answers "has this file already been migrated" across runs, which is what stops
-- a second run duplicating a first one's work.
CREATE INDEX ix_media_migration_items_path
    ON media.migration_items (tenant_id, normalized_path);
GRANT SELECT, INSERT, UPDATE ON media.migration_items TO qoida_application;
