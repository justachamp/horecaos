-- Row 10.12 (batch 14, w2-locales-part-3): a preset product comment carries its
-- wording in any locale a tenant supports, not only the platform triple.
--
-- catalog.comment_presets (V0378) stores label_ru / label_uz / label_en as three
-- NOT NULL columns, so the console's preset editor was hard-coded to those three
-- inputs no matter which languages the tenant's brands actually offer, and could
-- not carry a fourth locale at all. This is the per-locale table every other
-- localized row in the schema uses (V0016 catalog.translations, V0244
-- payments.payment_method_translations, V0401 tenant.location_content): one row per
-- (preset, locale).
--
-- WRITTEN ALONGSIDE THE COLUMNS FOR ONE RELEASE. The three label_* columns stay
-- the source of truth for the platform triple (ru / uz-Latn / en): checkout still
-- snapshots them onto an order line (V0397), the storefront reads them, and both
-- are NOT NULL. This table (a) receives every label the preset service writes,
-- triple included, so a later release can drop the columns and read from here
-- alone without a backfill, and (b) is the only home of a locale outside the
-- triple. Reads merge the two with the column winning for a triple locale, so a
-- locale is never reported twice.
--
-- The locale is a well-formed BCP 47 tag (ru, uz-Latn, kaa, ...), not the closed
-- triple V0244 checks: this table exists so a fourth language does not need a
-- migration. Which locales a tenant may author is the tenant's brands' supported
-- set (tenant.brand_locales, V0242), enforced by the console, not by the schema.
CREATE TABLE catalog.comment_preset_translations (
    tenant_id uuid NOT NULL,
    preset_id uuid NOT NULL,
    locale varchar(16) NOT NULL,
    label varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (preset_id, locale),
    CONSTRAINT ck_comment_preset_translation_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_comment_preset_translation_label CHECK (length(btrim(label)) > 0),
    -- (preset_id, tenant_id) rather than preset_id alone, the same composite-key
    -- discipline V0378's own product_comment_presets keeps: a translation row can
    -- never be attached to another tenant's preset even if a caller passed the
    -- wrong tenant_id.
    CONSTRAINT fk_comment_preset_translation_preset FOREIGN KEY (preset_id, tenant_id)
        REFERENCES catalog.comment_presets (id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ix_comment_preset_translations_tenant
    ON catalog.comment_preset_translations (tenant_id, preset_id);

COMMENT ON TABLE catalog.comment_preset_translations IS
    'Row 10.12. A preset product comment''s label per locale. Written alongside catalog.comment_presets.label_ru/label_uz/label_en for one release; the columns stay the source for the platform triple, this table is the only home of any other locale, and a read merges both with the column winning.';

GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.comment_preset_translations TO horecaos_application;

-- Seed the table from the columns so the release that drops them has nothing to
-- backfill. A blank column (the API never allowed one, but no CHECK stopped a
-- hand-written row) is skipped rather than failing ck_..._label.
INSERT INTO catalog.comment_preset_translations (tenant_id, preset_id, locale, label)
SELECT tenant_id, id, 'ru', label_ru FROM catalog.comment_presets WHERE length(btrim(label_ru)) > 0
UNION ALL
SELECT tenant_id, id, 'uz-Latn', label_uz FROM catalog.comment_presets WHERE length(btrim(label_uz)) > 0
UNION ALL
SELECT tenant_id, id, 'en', label_en FROM catalog.comment_presets WHERE length(btrim(label_en)) > 0;
