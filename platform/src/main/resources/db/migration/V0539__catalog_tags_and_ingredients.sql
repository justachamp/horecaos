-- ADR 0174 (Accepted 2026-10-07), gap-map row 4.7: the two reference vocabularies the row names that have no
-- table -- tags and ingredients -- as small tenant-wide registries with brand-scoped assignments.
--
-- Attributes are not built and not a variant axis (the variant's own translated name is the axis label, which
-- is published from this change on); kitchen departments, comment presets, recommendations and brands were
-- built under other records and are only linked from the console.
--
-- The shape follows catalog.comment_presets (V0378, V0430): tenant-wide because "spicy" means the same at every
-- brand of a chain; a code unique per tenant that never changes; archived and never deleted; the wording in a
-- per-locale translations table from the start, with no label columns beside it, so a fourth language needs no
-- migration. The assignment tables carry brand_id through the product, exactly as product_comment_presets does,
-- so a brand publishes only what its own products use.
--
-- An ingredient is a customer-facing composition LABEL and nothing else: no allergen column exists and none is
-- to be added here (an allergen declaration is its own decision with its own legal review), no price, no stock,
-- no quantity. It is not a modifier and not a bill of materials.

-- ----------------------------------------------------------------------------------------------- tags

CREATE TABLE catalog.tags (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    -- Operator-chosen, stable, never shown to a customer. A rename changes the name, not the code.
    code varchar(32) NOT NULL,
    -- A key into the closed, code-owned pictogram list (the service checks it); no uploaded image.
    icon_key varchar(24),
    show_as_badge boolean NOT NULL,
    show_as_filter boolean NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_tag_code CHECK (code ~ '^[a-z0-9][a-z0-9_-]{0,31}$'),
    CONSTRAINT ck_tag_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_tag_version CHECK (version >= 1),
    -- A tag that is neither shown nor offered as a filter does nothing.
    CONSTRAINT ck_tag_visible CHECK (show_as_badge OR show_as_filter),
    CONSTRAINT uq_tag_code UNIQUE (tenant_id, code),
    -- The target of the composite foreign keys below.
    CONSTRAINT uq_tag_identity UNIQUE (id, tenant_id)
);

CREATE INDEX ix_tags_tenant ON catalog.tags (tenant_id, sort_order);

COMMENT ON TABLE catalog.tags IS
    'ADR 0174. A tenant-wide registry of badges and storefront filter options. Archived, never deleted: an archived tag keeps resolving on the products that carry it and leaves the next publication.';

CREATE TABLE catalog.tag_translations (
    tenant_id uuid NOT NULL,
    tag_id uuid NOT NULL,
    locale varchar(16) NOT NULL,
    name varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tag_id, locale),
    CONSTRAINT ck_tag_translation_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_tag_translation_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT fk_tag_translation_tag FOREIGN KEY (tag_id, tenant_id)
        REFERENCES catalog.tags (id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ix_tag_translations_tenant ON catalog.tag_translations (tenant_id, tag_id);

COMMENT ON TABLE catalog.tag_translations IS
    'ADR 0174. A tag''s name per locale. There are no label columns beside this table, so a new language needs no migration.';

CREATE TABLE catalog.product_tags (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    product_id uuid NOT NULL,
    tag_id uuid NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_product_tag_version CHECK (version >= 1),
    CONSTRAINT fk_product_tag_product FOREIGN KEY (product_id, tenant_id, brand_id)
        REFERENCES catalog.products (id, tenant_id, brand_id),
    CONSTRAINT fk_product_tag_tag FOREIGN KEY (tag_id, tenant_id)
        REFERENCES catalog.tags (id, tenant_id),
    CONSTRAINT uq_product_tag UNIQUE (product_id, tag_id)
);

CREATE INDEX ix_product_tags_product ON catalog.product_tags (tenant_id, product_id, sort_order);
CREATE INDEX ix_product_tags_tag ON catalog.product_tags (tenant_id, tag_id);

COMMENT ON TABLE catalog.product_tags IS
    'ADR 0174. The ordered set of tags a product carries (at most twelve, enforced by the service). Replaced as a set.';

-- ---------------------------------------------------------------------------------------- ingredients

CREATE TABLE catalog.ingredients (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    code varchar(32) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_ingredient_code CHECK (code ~ '^[a-z0-9][a-z0-9_-]{0,31}$'),
    CONSTRAINT ck_ingredient_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_ingredient_version CHECK (version >= 1),
    CONSTRAINT uq_ingredient_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_ingredient_identity UNIQUE (id, tenant_id)
);

CREATE INDEX ix_ingredients_tenant ON catalog.ingredients (tenant_id, sort_order);

COMMENT ON TABLE catalog.ingredients IS
    'ADR 0174. A tenant-wide registry of composition labels shown on a product page. Not priced, stocked, ordered or counted; not a modifier, not a bill of materials, and deliberately no allergen meaning.';

CREATE TABLE catalog.ingredient_translations (
    tenant_id uuid NOT NULL,
    ingredient_id uuid NOT NULL,
    locale varchar(16) NOT NULL,
    name varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (ingredient_id, locale),
    CONSTRAINT ck_ingredient_translation_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_ingredient_translation_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT fk_ingredient_translation_ingredient FOREIGN KEY (ingredient_id, tenant_id)
        REFERENCES catalog.ingredients (id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX ix_ingredient_translations_tenant ON catalog.ingredient_translations (tenant_id, ingredient_id);

COMMENT ON TABLE catalog.ingredient_translations IS
    'ADR 0174. An ingredient''s name per locale.';

CREATE TABLE catalog.product_ingredients (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    product_id uuid NOT NULL,
    ingredient_id uuid NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_product_ingredient_version CHECK (version >= 1),
    CONSTRAINT fk_product_ingredient_product FOREIGN KEY (product_id, tenant_id, brand_id)
        REFERENCES catalog.products (id, tenant_id, brand_id),
    CONSTRAINT fk_product_ingredient_ingredient FOREIGN KEY (ingredient_id, tenant_id)
        REFERENCES catalog.ingredients (id, tenant_id),
    CONSTRAINT uq_product_ingredient UNIQUE (product_id, ingredient_id)
);

CREATE INDEX ix_product_ingredients_product ON catalog.product_ingredients (tenant_id, product_id, sort_order);
CREATE INDEX ix_product_ingredients_ingredient ON catalog.product_ingredients (tenant_id, ingredient_id);

COMMENT ON TABLE catalog.product_ingredients IS
    'ADR 0174. The ordered list of composition labels a product shows (at most forty, enforced by the service). Replaced as a set.';

-- --------------------------------------------------------------------------------------------- grants
--
-- A vocabulary entry is archived, not deleted, so the registries take no DELETE. An assignment is replaced as a
-- set and a translation may be dropped with its entry's cascade, so those two take DELETE.
GRANT SELECT, INSERT, UPDATE ON catalog.tags TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON catalog.ingredients TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.tag_translations TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.ingredient_translations TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.product_tags TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.product_ingredients TO horecaos_application;
