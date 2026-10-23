-- Settings 10.11 (batch 21, w10-privacy), ADR 0149 Decision 4 and ADR 0109 Decision 3: a consent purpose is worded in every
-- language the tenant's brands offer, not in the three the platform launched with.
--
-- customer.consent_types (V0289) kept a purpose's name in label_ru, label_uz and label_en, all NOT NULL, so the registry could
-- not carry a fourth language and the console's card switched on those three columns. This is the per-locale table every other
-- localized row in the schema uses (V0016, V0244, V0430, V0431, V0543): one row per (purpose, locale), the locale a well-formed
-- BCP 47 tag, not the closed triple.
--
-- WRITTEN ALONGSIDE THE COLUMNS FOR ONE RELEASE, as V0430 did. The three label_* columns stay written for the platform triple so
-- the previous build, if a rollback is needed, still finds the wording it reads; they stop being NOT NULL, because a tenant whose
-- default language is outside the triple has nothing to put in them. Reads merge the two homes with the translations table
-- winning, and the release that drops the columns has nothing to backfill: this migration copies every existing row.

-- A foreign key must reference a unique constraint on exactly its own columns: (id, tenant_id) is the pair V0289's
-- primary key and tenant unique constraint do not give as one.
ALTER TABLE customer.consent_types ADD CONSTRAINT uq_consent_type_identity UNIQUE (id, tenant_id);

CREATE TABLE customer.consent_type_translations (
    tenant_id uuid NOT NULL,
    consent_type_id uuid NOT NULL,
    locale varchar(16) NOT NULL,
    label varchar(200) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (consent_type_id, locale),
    CONSTRAINT ck_consent_type_translation_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_consent_type_translation_label CHECK (length(btrim(label)) > 0),
    -- (consent_type_id, tenant_id) rather than the id alone, the composite-key discipline the sibling translation tables keep: a
    -- translation can never be attached to another tenant's purpose even if a caller passed the wrong tenant_id.
    CONSTRAINT fk_consent_type_translation_type FOREIGN KEY (consent_type_id, tenant_id)
        REFERENCES customer.consent_types (id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ix_consent_type_translations_tenant
    ON customer.consent_type_translations (tenant_id, consent_type_id);

COMMENT ON TABLE customer.consent_type_translations IS
    'Settings 10.11, ADR 0149. A consent purpose''s name per locale. Written alongside customer.consent_types.label_ru/label_uz/label_en for one release; this table is the only home of a locale outside the platform triple, and a read merges both with this table winning.';

GRANT SELECT, INSERT, UPDATE, DELETE ON customer.consent_type_translations TO horecaos_application;

-- Copy what exists, so the release that drops the columns has nothing to backfill. A blank column (no CHECK ever stopped a
-- hand-written one) is skipped rather than failing ck_consent_type_translation_label.
INSERT INTO customer.consent_type_translations (tenant_id, consent_type_id, locale, label)
SELECT tenant_id, id, 'ru', label_ru FROM customer.consent_types WHERE length(btrim(label_ru)) > 0
UNION ALL
SELECT tenant_id, id, 'uz-Latn', label_uz FROM customer.consent_types WHERE length(btrim(label_uz)) > 0
UNION ALL
SELECT tenant_id, id, 'en', label_en FROM customer.consent_types WHERE length(btrim(label_en)) > 0;

ALTER TABLE customer.consent_types ALTER COLUMN label_ru DROP NOT NULL;
ALTER TABLE customer.consent_types ALTER COLUMN label_uz DROP NOT NULL;
ALTER TABLE customer.consent_types ALTER COLUMN label_en DROP NOT NULL;

COMMENT ON COLUMN customer.consent_types.label_ru IS
    'Deprecated by V0683, ADR 0149: kept written for the platform triple for one release. The name per locale is customer.consent_type_translations.';
COMMENT ON COLUMN customer.consent_types.label_uz IS
    'Deprecated by V0683, ADR 0149: the uz-Latn wording, kept written for one release. See customer.consent_type_translations.';
COMMENT ON COLUMN customer.consent_types.label_en IS
    'Deprecated by V0683, ADR 0149: the en wording, kept written for one release. See customer.consent_type_translations.';
