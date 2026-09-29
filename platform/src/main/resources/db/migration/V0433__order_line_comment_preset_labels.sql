-- Row 10.12 (batch 15, w2-locale-consumption): an order line's preset snapshot carries
-- the wording of a locale beyond the platform triple.
--
-- ordering.order_line_comment_presets (V0397) freezes a preset's wording onto the order
-- line at checkout in three NOT NULL columns, label_ru / label_uz / label_en. V0430 gave
-- the preset itself a per-locale table so a tenant whose brands support a fourth language
-- can word a preset in it, but checkout read only the three columns, so that wording
-- stopped at the catalog: the receipt, the kitchen chip and the order detail could never
-- show it, and once a preset was renamed nothing could reconstruct it.
--
-- The three snapshot columns stay the source for the platform triple (a read merges both
-- with the column winning, the rule V0430 documents), and this table is the only home of
-- any other locale -- one row per snapshot and locale, copied at checkout and never
-- edited, INSERT-only for the same reason V0397 is: a snapshot does not follow the row it
-- was copied from.
--
-- A child table rather than a jsonb column so nothing in the ordering module needs a JSON
-- mapper to write or read it, and so the locale is held to the same well-formed BCP 47
-- shape every other per-locale table in the schema (V0430, V0431) is.

-- The composite key the child's foreign key needs. tenant_id rides along in every
-- reference into a tenant-scoped table, so a label row can never be attached to another
-- tenant's snapshot even if a caller passed the wrong tenant id. id is already the primary
-- key, so this adds a constraint, not a restriction.
ALTER TABLE ordering.order_line_comment_presets
    ADD CONSTRAINT uq_order_line_comment_preset_identity UNIQUE (id, tenant_id);

CREATE TABLE ordering.order_line_comment_preset_labels (
    tenant_id uuid NOT NULL,
    order_line_comment_preset_id uuid NOT NULL,
    locale varchar(16) NOT NULL,
    label varchar(120) NOT NULL,

    PRIMARY KEY (order_line_comment_preset_id, locale),
    CONSTRAINT ck_order_line_comment_preset_label_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_order_line_comment_preset_label_text CHECK (length(btrim(label)) > 0),
    CONSTRAINT fk_order_line_comment_preset_label_snapshot
        FOREIGN KEY (order_line_comment_preset_id, tenant_id)
        REFERENCES ordering.order_line_comment_presets (id, tenant_id)
);

CREATE INDEX ix_order_line_comment_preset_labels_tenant
    ON ordering.order_line_comment_preset_labels (tenant_id, order_line_comment_preset_id);

COMMENT ON TABLE ordering.order_line_comment_preset_labels IS
    'Row 10.12. The wording of a locale beyond the platform triple that an order line''s preset snapshot (V0397) was checked out carrying. The three label_*_snapshot columns stay the source for the triple; a read merges both with the column winning. INSERT-only: a snapshot is never edited.';

GRANT SELECT, INSERT ON ordering.order_line_comment_preset_labels TO horecaos_application;
