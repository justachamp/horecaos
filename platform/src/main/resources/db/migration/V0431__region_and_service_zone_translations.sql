-- Row 10.12 (batch 14, w2-locales-part-3): delivery regions and zones carry their
-- display name in any locale a tenant supports, not only the platform triple.
--
-- fulfillment.regions (V0025) and fulfillment.service_zones (V0025) store
-- display_name_ru / display_name_uz / display_name_en as three NOT NULL columns.
-- The same shape, and the same one-release transition, as V0430's
-- catalog.comment_preset_translations: these tables are written alongside the
-- columns, the columns stay the source for the platform triple, the tables are the
-- only home of any other locale, and a read merges both with the column winning.
-- See V0430 for the reasoning in full; only what differs is said here.

-- ---------------------------------------------------------------------------
-- Regions
-- ---------------------------------------------------------------------------
--
-- Only a TENANT-OWNED region has translation rows. A platform region (tenant_id
-- null, "Tashkent is not one tenant's fact") is not editable by a tenant, so there
-- is no tenant-facing writer to put a row here, and a tenant_id that must be a real
-- tenant makes that structural: the foreign key below matches
-- regions.owner_tenant_id (V0088), which is the nil uuid for a platform region and
-- can never equal a tenant.tenants id.
CREATE TABLE fulfillment.region_translations (
    tenant_id uuid NOT NULL,
    region_id uuid NOT NULL,
    locale varchar(16) NOT NULL,
    display_name varchar(200) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (region_id, locale),
    CONSTRAINT ck_region_translation_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_region_translation_name CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT fk_region_translation_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT fk_region_translation_region FOREIGN KEY (tenant_id, region_id)
        REFERENCES fulfillment.regions (owner_tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_region_translations_tenant ON fulfillment.region_translations (tenant_id, region_id);

COMMENT ON TABLE fulfillment.region_translations IS
    'Row 10.12. A tenant-owned region''s display name per locale. Written alongside fulfillment.regions.display_name_ru/uz/en for one release; the columns stay the source for the platform triple and a read merges both with the column winning. Platform regions (tenant_id null) never have rows here.';

GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.region_translations TO horecaos_application;

INSERT INTO fulfillment.region_translations (tenant_id, region_id, locale, display_name)
SELECT tenant_id, id, 'ru', display_name_ru
  FROM fulfillment.regions WHERE tenant_id IS NOT NULL AND length(btrim(display_name_ru)) > 0
UNION ALL
SELECT tenant_id, id, 'uz-Latn', display_name_uz
  FROM fulfillment.regions WHERE tenant_id IS NOT NULL AND length(btrim(display_name_uz)) > 0
UNION ALL
SELECT tenant_id, id, 'en', display_name_en
  FROM fulfillment.regions WHERE tenant_id IS NOT NULL AND length(btrim(display_name_en)) > 0;

-- ---------------------------------------------------------------------------
-- Service zones
-- ---------------------------------------------------------------------------
--
-- A zone is brand-scoped and its names are edited in its brand's own supported
-- locale set. brand_id rides along (and in the foreign key) for the same reason
-- every other brand-owned table in this schema carries it: a translation row
-- cannot name a zone under a brand it does not belong to.
CREATE TABLE fulfillment.service_zone_translations (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    zone_id uuid NOT NULL,
    locale varchar(16) NOT NULL,
    display_name varchar(200) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (zone_id, locale),
    CONSTRAINT ck_service_zone_translation_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_service_zone_translation_name CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT fk_service_zone_translation_zone FOREIGN KEY (tenant_id, brand_id, zone_id)
        REFERENCES fulfillment.service_zones (tenant_id, brand_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_service_zone_translations_brand
    ON fulfillment.service_zone_translations (tenant_id, brand_id, zone_id);

COMMENT ON TABLE fulfillment.service_zone_translations IS
    'Row 10.12. A delivery or catchment zone''s display name per locale. Written alongside fulfillment.service_zones.display_name_ru/uz/en for one release; the columns stay the source for the platform triple and a read merges both with the column winning.';

GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.service_zone_translations TO horecaos_application;

INSERT INTO fulfillment.service_zone_translations (tenant_id, brand_id, zone_id, locale, display_name)
SELECT tenant_id, brand_id, id, 'ru', display_name_ru
  FROM fulfillment.service_zones WHERE length(btrim(display_name_ru)) > 0
UNION ALL
SELECT tenant_id, brand_id, id, 'uz-Latn', display_name_uz
  FROM fulfillment.service_zones WHERE length(btrim(display_name_uz)) > 0
UNION ALL
SELECT tenant_id, brand_id, id, 'en', display_name_en
  FROM fulfillment.service_zones WHERE length(btrim(display_name_en)) > 0;
