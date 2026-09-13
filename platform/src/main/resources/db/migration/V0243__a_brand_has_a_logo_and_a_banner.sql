-- Settings 10.1 (operations-gap-map P32): no logo or banner concept exists
-- anywhere in the schema for a brand. ADR 0010's media relation pattern
-- (catalog.media_relations, V0016) is owned by the catalog module and its own
-- `entity_type` CHECK is closed over catalog entities -- this table is
-- tenancy's own equivalent rather than an addition to another module's table,
-- the same boundary V0016's comment on that table draws.
--
-- media.assets already accepts owner_scope = 'BRAND' (V0015), so an asset is
-- allocated and verified exactly the way a catalog image is; this table only
-- records which verified asset plays which role for which brand.
CREATE TABLE tenant.brand_media (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- One asset per role per brand: a brand has exactly one current logo and
    -- one current banner, never a gallery of either.
    role varchar(16) NOT NULL,
    media_asset_id uuid NOT NULL,

    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (brand_id, role),
    CONSTRAINT fk_brand_media_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT ck_brand_media_role CHECK (role IN ('LOGO', 'BANNER'))
);

COMMENT ON TABLE tenant.brand_media IS
    '10.1. Which media.assets row is this brand''s current logo or aggregator/QR banner. ADR 0010: attached by reference, never a URL column.';

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.brand_media TO horecaos_application;
