-- ADR 0010: media metadata in PostgreSQL, bytes in a private object store.
--
-- The rule this schema exists to enforce is that a business module references a
-- media_asset_id and nothing else. The legacy system stored filesystem paths in
-- business tables, which is why moving storage there means rewriting every
-- module that ever displayed an image.

CREATE SCHEMA IF NOT EXISTS media;

CREATE TABLE media.assets (
    asset_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- Who may attach this asset. Ownership is checked on every presign and
    -- every attach, because a tenant that can guess another tenant's asset id
    -- must still not be able to read or reuse it.
    owner_scope varchar(32) NOT NULL,
    owner_id uuid NOT NULL,

    -- The object key is immutable and allocated by us, never supplied by the
    -- client. A client-chosen key is a path-traversal and overwrite vector.
    object_key varchar(512) NOT NULL,
    bucket varchar(128) NOT NULL,

    status varchar(32) NOT NULL,
    visibility varchar(16) NOT NULL,

    -- Declared at allocation, verified at finalize against the store's own
    -- metadata. The declared values are a constraint on the presigned URL, not
    -- evidence of what was uploaded.
    declared_content_type varchar(128) NOT NULL,
    declared_size_bytes bigint NOT NULL,
    declared_checksum_sha256 varchar(64),

    -- Read back from the object store at finalize. These are the trusted ones.
    verified_content_type varchar(128),
    verified_size_bytes bigint,
    verified_checksum_sha256 varchar(64),

    original_filename varchar(255),
    width_px integer,
    height_px integer,

    rejection_code varchar(64),
    rejection_detail varchar(1000),

    created_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    finalized_at timestamptz,
    available_at timestamptz,
    deleted_at timestamptz,

    CONSTRAINT ck_media_asset_owner_scope CHECK (
        owner_scope IN ('TENANT', 'BRAND', 'LOCATION')
    ),
    CONSTRAINT ck_media_asset_status CHECK (
        status IN ('PENDING_UPLOAD', 'UPLOADED', 'AVAILABLE', 'REJECTED', 'DELETION_REQUESTED', 'DELETED')
    ),
    CONSTRAINT ck_media_asset_visibility CHECK (
        visibility IN ('PUBLIC', 'PRIVATE')
    ),
    CONSTRAINT ck_media_asset_size CHECK (declared_size_bytes > 0),
    -- A rejected asset must say why. Without this a rejection is an unexplained
    -- dead end for whoever tried to upload.
    CONSTRAINT ck_media_asset_rejection CHECK (
        (status <> 'REJECTED') OR (rejection_code IS NOT NULL)
    ),
    -- Available means verified. The trusted columns cannot be null once we have
    -- told a storefront the image is safe to show.
    CONSTRAINT ck_media_asset_verified CHECK (
        (status <> 'AVAILABLE') OR (
            verified_content_type IS NOT NULL
            AND verified_size_bytes IS NOT NULL
            AND verified_checksum_sha256 IS NOT NULL
        )
    )
);

-- One object per key, globally. Keys embed the tenant, so this also prevents a
-- key collision from letting one tenant's finalize claim another's object.
CREATE UNIQUE INDEX ux_media_assets_key ON media.assets (bucket, object_key);

CREATE INDEX ix_media_assets_owner
    ON media.assets (tenant_id, owner_scope, owner_id)
    WHERE status <> 'DELETED';

-- Drives the reaper for uploads that were allocated and never completed.
CREATE INDEX ix_media_assets_pending
    ON media.assets (created_at)
    WHERE status = 'PENDING_UPLOAD';

COMMENT ON COLUMN media.assets.declared_checksum_sha256 IS
    'What the client said it would upload. Never treated as proof; compared against the store''s own checksum at finalize.';
COMMENT ON COLUMN media.assets.verified_checksum_sha256 IS
    'Read from the object store after upload. This is the trusted value.';

GRANT USAGE ON SCHEMA media TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON media.assets TO qoida_application;
