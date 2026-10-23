-- Row 10.12 (ADR 0149, Decision 3; wave 21): the labels map becomes the only storage shape of a preset
-- comment's wording, as V0430 and V0433 promised ("so a later release can drop the columns and read from
-- here alone").
--
-- catalog.comment_presets (V0378) kept label_ru / label_uz / label_en as three NOT NULL columns, and the
-- order line's snapshot (V0397) kept label_ru_snapshot / label_uz_snapshot / label_en_snapshot. Both were
-- written alongside a per-locale table for one release (catalog.comment_preset_translations, V0430;
-- ordering.order_line_comment_preset_labels, V0433) so that a language beyond the platform triple needed no
-- migration. The columns made every reader merge two homes with the column winning, made the console's
-- editor and the API's contract carry three fields no brand that serves a fourth language can use, and made
-- a write for a language a brand does not serve copy the default wording into a column to satisfy NOT NULL.
-- They go. No production tenant exists (ADR 0055), so this is one step and not an expand-and-contract.
--
-- 1. The catalog's presets already hold the triple in the translations table (V0430 seeded it, and the
--    preset service has written every supplied label there since). Anything a hand-written row left only in
--    a column is copied across first, never over a row that is already there.
INSERT INTO catalog.comment_preset_translations (tenant_id, preset_id, locale, label)
SELECT tenant_id, id, 'ru', btrim(label_ru) FROM catalog.comment_presets WHERE length(btrim(label_ru)) > 0
UNION ALL
SELECT tenant_id, id, 'uz-Latn', btrim(label_uz) FROM catalog.comment_presets WHERE length(btrim(label_uz)) > 0
UNION ALL
SELECT tenant_id, id, 'en', btrim(label_en) FROM catalog.comment_presets WHERE length(btrim(label_en)) > 0
ON CONFLICT (preset_id, locale) DO NOTHING;

-- 2. An order line's snapshot never wrote the triple to its labels table (V0433: "the three columns stay the
--    source for the triple"), so the triple is copied from the columns before they go. A snapshot is
--    INSERT-only and this keeps it so: no existing label row is touched.
INSERT INTO ordering.order_line_comment_preset_labels (tenant_id, order_line_comment_preset_id, locale, label)
SELECT tenant_id, id, 'ru', btrim(label_ru_snapshot)
FROM ordering.order_line_comment_presets WHERE length(btrim(label_ru_snapshot)) > 0
UNION ALL
SELECT tenant_id, id, 'uz-Latn', btrim(label_uz_snapshot)
FROM ordering.order_line_comment_presets WHERE length(btrim(label_uz_snapshot)) > 0
UNION ALL
SELECT tenant_id, id, 'en', btrim(label_en_snapshot)
FROM ordering.order_line_comment_presets WHERE length(btrim(label_en_snapshot)) > 0
ON CONFLICT (order_line_comment_preset_id, locale) DO NOTHING;

-- 3. The columns.
ALTER TABLE catalog.comment_presets
    DROP COLUMN label_ru,
    DROP COLUMN label_uz,
    DROP COLUMN label_en;

ALTER TABLE ordering.order_line_comment_presets
    DROP COLUMN label_ru_snapshot,
    DROP COLUMN label_uz_snapshot,
    DROP COLUMN label_en_snapshot;

COMMENT ON TABLE catalog.comment_preset_translations IS
    'Row 10.12. A preset product comment''s wording per locale: the only home of it since V0678. A preset needs one in the tenant''s default language; a language outside the platform triple needs no migration.';

COMMENT ON TABLE ordering.order_line_comment_preset_labels IS
    'Row 10.12. The wording, per locale, that an order line''s preset snapshot (V0397) was checked out carrying: the only home of it since V0678. INSERT-only: a snapshot is never edited.';
