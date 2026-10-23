-- Row 10.12 (ADR 0149, Decision 3; wave 21): the per-locale tables become the only home of a delivery region's
-- and a service zone's name, as V0431 promised ("so a later release can drop the columns and read from the
-- tables alone").
--
-- fulfillment.regions and fulfillment.service_zones (V0025) kept display_name_ru / display_name_uz /
-- display_name_en as three NOT NULL columns, and V0431 added region_translations and service_zone_translations
-- beside them for one release, so a fourth language needed no migration. The columns made every reader merge
-- two homes with the column winning, made the console and the API carry three fields no brand serving a fourth
-- language can use, and forced the default name into a column for a language a brand does not serve. They go,
-- in their own migration and separately from V0678's preset columns: a region and a zone have a different
-- ownership story (a platform region has no tenant), and one failing must not hold the other. No production
-- tenant exists (ADR 0055), so this is one step and not an expand-and-contract.
--
-- 1. Service zones are tenant- and brand-owned, and V0431 seeded and the service has written every supplied name
--    to service_zone_translations since. Anything a hand-written row left only in a column is copied across
--    first, never over a row that is already there.
INSERT INTO fulfillment.service_zone_translations (tenant_id, brand_id, zone_id, locale, display_name)
SELECT tenant_id, brand_id, id, 'ru', btrim(display_name_ru)
  FROM fulfillment.service_zones WHERE length(btrim(display_name_ru)) > 0
UNION ALL
SELECT tenant_id, brand_id, id, 'uz-Latn', btrim(display_name_uz)
  FROM fulfillment.service_zones WHERE length(btrim(display_name_uz)) > 0
UNION ALL
SELECT tenant_id, brand_id, id, 'en', btrim(display_name_en)
  FROM fulfillment.service_zones WHERE length(btrim(display_name_en)) > 0
ON CONFLICT (zone_id, locale) DO NOTHING;

-- 2. Tenant-owned regions likewise, into region_translations.
INSERT INTO fulfillment.region_translations (tenant_id, region_id, locale, display_name)
SELECT tenant_id, id, 'ru', btrim(display_name_ru)
  FROM fulfillment.regions WHERE tenant_id IS NOT NULL AND length(btrim(display_name_ru)) > 0
UNION ALL
SELECT tenant_id, id, 'uz-Latn', btrim(display_name_uz)
  FROM fulfillment.regions WHERE tenant_id IS NOT NULL AND length(btrim(display_name_uz)) > 0
UNION ALL
SELECT tenant_id, id, 'en', btrim(display_name_en)
  FROM fulfillment.regions WHERE tenant_id IS NOT NULL AND length(btrim(display_name_en)) > 0
ON CONFLICT (region_id, locale) DO NOTHING;

-- 3. A platform region ("Tashkent is not one tenant's fact", V0025) has no tenant, so region_translations
--    cannot hold its name: that table's foreign key names a real tenant on purpose (V0431). Its three columns
--    were the only home of its name, so dropping them needs another. This is platform reference data, written
--    by the platform's operator and read by every tenant; no tenant writes it, and nothing here is tenant data
--    for ADR 0056 to isolate. The owner is the nil uuid V0088 gave a platform region, fixed by a CHECK and
--    carried in the foreign key, so a row can only ever name a platform region.
CREATE TABLE fulfillment.platform_region_names (
    owner_tenant_id uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    region_id uuid NOT NULL,
    locale varchar(16) NOT NULL,
    display_name varchar(200) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (region_id, locale),
    CONSTRAINT ck_platform_region_name_owner CHECK (owner_tenant_id = '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT ck_platform_region_name_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_platform_region_name_text CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT fk_platform_region_name_region FOREIGN KEY (owner_tenant_id, region_id)
        REFERENCES fulfillment.regions (owner_tenant_id, id) ON DELETE CASCADE
);

COMMENT ON TABLE fulfillment.platform_region_names IS
    'Row 10.12, V0679. A platform region''s name per locale (a platform region has no tenant, so region_translations cannot hold it). Platform reference data: written by the platform''s operator, read by every tenant, never by a tenant''s own request.';

GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.platform_region_names TO horecaos_application;

INSERT INTO fulfillment.platform_region_names (region_id, locale, display_name)
SELECT id, 'ru', btrim(display_name_ru) FROM fulfillment.regions
 WHERE tenant_id IS NULL AND length(btrim(display_name_ru)) > 0
UNION ALL
SELECT id, 'uz-Latn', btrim(display_name_uz) FROM fulfillment.regions
 WHERE tenant_id IS NULL AND length(btrim(display_name_uz)) > 0
UNION ALL
SELECT id, 'en', btrim(display_name_en) FROM fulfillment.regions
 WHERE tenant_id IS NULL AND length(btrim(display_name_en)) > 0;

-- 4. The columns.
ALTER TABLE fulfillment.regions
    DROP COLUMN display_name_ru,
    DROP COLUMN display_name_uz,
    DROP COLUMN display_name_en;

ALTER TABLE fulfillment.service_zones
    DROP COLUMN display_name_ru,
    DROP COLUMN display_name_uz,
    DROP COLUMN display_name_en;

COMMENT ON TABLE fulfillment.region_translations IS
    'Row 10.12. A tenant-owned region''s display name per locale: the only home of it since V0679. A platform region''s is in platform_region_names.';

COMMENT ON TABLE fulfillment.service_zone_translations IS
    'Row 10.12. A delivery or catchment zone''s display name per locale: the only home of it since V0679.';
