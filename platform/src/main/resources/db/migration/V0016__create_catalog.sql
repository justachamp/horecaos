-- ADR 0016: brand catalog authoring and versioned publication.
--
-- Two rules shape every table here.
--
-- First, everything is brand-owned and every foreign key carries tenant_id and
-- brand_id. A composite key makes a cross-tenant reference impossible at the
-- database level rather than dependent on a service remembering to check.
--
-- Second, authoring and publication are separate. A draft edit must not be able
-- to reach a live menu, and the only way to guarantee that is for the storefront
-- to read a different, immutable set of rows.

CREATE SCHEMA IF NOT EXISTS catalog;

-- A brand's container for products. A brand may have several (seasonal menus,
-- a delivery-only menu), which is why this is not simply the brand itself.
CREATE TABLE catalog.catalogs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    code varchar(64) NOT NULL,
    name varchar(255) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_catalog_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT uq_catalog_code UNIQUE (tenant_id, brand_id, code),
    -- The target of every composite foreign key below.
    CONSTRAINT uq_catalog_identity UNIQUE (id, tenant_id, brand_id)
);

-- Products are brand-owned rather than catalog-owned, so one product can appear
-- in several of a brand's catalogs without being duplicated. Duplicating it is
-- how two menus drift into disagreeing about the same dish.
CREATE TABLE catalog.products (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    code varchar(64) NOT NULL,
    tax_category_code varchar(32),
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_product_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT uq_product_code UNIQUE (tenant_id, brand_id, code),
    CONSTRAINT uq_product_identity UNIQUE (id, tenant_id, brand_id)
);

-- The sellable unit. Price lives in pricing and quantity in inventory; a variant
-- says what can be ordered, not what it costs or whether any is left.
CREATE TABLE catalog.variants (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    product_id uuid NOT NULL,
    sku varchar(64),
    unit_code varchar(16) NOT NULL DEFAULT 'PIECE',
    is_default boolean NOT NULL DEFAULT false,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_variant_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT fk_variant_product FOREIGN KEY (product_id, tenant_id, brand_id)
        REFERENCES catalog.products (id, tenant_id, brand_id),
    CONSTRAINT uq_variant_sku UNIQUE (tenant_id, brand_id, sku),
    CONSTRAINT uq_variant_identity UNIQUE (id, tenant_id, brand_id)
);

-- At most one default per product. A second default would make "what does the
-- customer get when they tap the product" ambiguous.
CREATE UNIQUE INDEX ux_variant_single_default
    ON catalog.variants (product_id) WHERE is_default;

CREATE TABLE catalog.categories (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    catalog_id uuid NOT NULL,
    parent_category_id uuid,
    code varchar(64) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_category_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    -- A category cannot be its own parent. Deeper cycles are caught by
    -- validation, which can report the whole path rather than just a row.
    CONSTRAINT ck_category_not_self_parent CHECK (parent_category_id <> id),
    CONSTRAINT fk_category_catalog FOREIGN KEY (catalog_id, tenant_id, brand_id)
        REFERENCES catalog.catalogs (id, tenant_id, brand_id),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_category_id, tenant_id, brand_id)
        REFERENCES catalog.categories (id, tenant_id, brand_id),
    CONSTRAINT uq_category_code UNIQUE (tenant_id, brand_id, catalog_id, code),
    CONSTRAINT uq_category_identity UNIQUE (id, tenant_id, brand_id)
);

CREATE TABLE catalog.modifier_groups (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    code varchar(64) NOT NULL,
    is_required boolean NOT NULL DEFAULT false,
    minimum_selections integer NOT NULL DEFAULT 0,
    maximum_selections integer NOT NULL DEFAULT 1,
    allow_same_option_multiple_times boolean NOT NULL DEFAULT false,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_modifier_group_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    -- An unsatisfiable range — minimum above maximum, or a required group that
    -- permits zero selections — would present the customer with a choice they
    -- cannot complete. Rejected here rather than at checkout.
    CONSTRAINT ck_modifier_group_range CHECK (
        minimum_selections >= 0
        AND maximum_selections >= 1
        AND minimum_selections <= maximum_selections
    ),
    CONSTRAINT ck_modifier_group_required CHECK (
        NOT is_required OR minimum_selections >= 1
    ),
    CONSTRAINT uq_modifier_group_code UNIQUE (tenant_id, brand_id, code),
    CONSTRAINT uq_modifier_group_identity UNIQUE (id, tenant_id, brand_id)
);

CREATE TABLE catalog.modifier_options (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    modifier_group_id uuid NOT NULL,
    code varchar(64) NOT NULL,
    -- A modifier that is itself a sellable thing (an extra shot, a side).
    -- Nullable because most modifiers are not.
    linked_variant_id uuid,
    maximum_quantity integer NOT NULL DEFAULT 1,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_modifier_option_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_modifier_option_quantity CHECK (maximum_quantity >= 1),
    CONSTRAINT fk_modifier_option_group FOREIGN KEY (modifier_group_id, tenant_id, brand_id)
        REFERENCES catalog.modifier_groups (id, tenant_id, brand_id),
    CONSTRAINT fk_modifier_option_variant FOREIGN KEY (linked_variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),
    CONSTRAINT uq_modifier_option_code UNIQUE (tenant_id, brand_id, modifier_group_id, code),
    CONSTRAINT uq_modifier_option_identity UNIQUE (id, tenant_id, brand_id)
);

-- Membership and ordering. Kept as explicit tables rather than arrays so a
-- single product can be reordered in one catalog without touching another.
CREATE TABLE catalog.catalog_products (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    catalog_id uuid NOT NULL,
    product_id uuid NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (catalog_id, product_id),
    CONSTRAINT fk_catalog_products_catalog FOREIGN KEY (catalog_id, tenant_id, brand_id)
        REFERENCES catalog.catalogs (id, tenant_id, brand_id),
    CONSTRAINT fk_catalog_products_product FOREIGN KEY (product_id, tenant_id, brand_id)
        REFERENCES catalog.products (id, tenant_id, brand_id)
);

CREATE TABLE catalog.category_products (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    category_id uuid NOT NULL,
    product_id uuid NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (category_id, product_id),
    CONSTRAINT fk_category_products_category FOREIGN KEY (category_id, tenant_id, brand_id)
        REFERENCES catalog.categories (id, tenant_id, brand_id),
    CONSTRAINT fk_category_products_product FOREIGN KEY (product_id, tenant_id, brand_id)
        REFERENCES catalog.products (id, tenant_id, brand_id)
);

CREATE TABLE catalog.product_modifier_groups (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    product_id uuid NOT NULL,
    modifier_group_id uuid NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id, modifier_group_id),
    CONSTRAINT fk_pmg_product FOREIGN KEY (product_id, tenant_id, brand_id)
        REFERENCES catalog.products (id, tenant_id, brand_id),
    CONSTRAINT fk_pmg_group FOREIGN KEY (modifier_group_id, tenant_id, brand_id)
        REFERENCES catalog.modifier_groups (id, tenant_id, brand_id)
);

CREATE TABLE catalog.variant_modifier_groups (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    variant_id uuid NOT NULL,
    modifier_group_id uuid NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (variant_id, modifier_group_id),
    CONSTRAINT fk_vmg_variant FOREIGN KEY (variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),
    CONSTRAINT fk_vmg_group FOREIGN KEY (modifier_group_id, tenant_id, brand_id)
        REFERENCES catalog.modifier_groups (id, tenant_id, brand_id)
);

-- Names live here, not on the entity. A missing required name blocks publication
-- rather than letting a database code reach a customer's screen.
CREATE TABLE catalog.translations (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    entity_type varchar(32) NOT NULL,
    entity_id uuid NOT NULL,
    locale varchar(16) NOT NULL,
    name varchar(255) NOT NULL,
    description varchar(2000),
    version integer NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (entity_type, entity_id, locale),
    CONSTRAINT ck_translation_entity_type CHECK (
        entity_type IN ('CATALOG', 'CATEGORY', 'PRODUCT', 'VARIANT', 'MODIFIER_GROUP', 'MODIFIER_OPTION')
    ),
    CONSTRAINT ck_translation_name CHECK (length(btrim(name)) > 0)
);

-- ADR 0010 media, attached by reference. Never a nullable URL column: a URL in a
-- business table is what made the legacy system's storage impossible to move.
CREATE TABLE catalog.media_relations (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    entity_type varchar(32) NOT NULL,
    entity_id uuid NOT NULL,
    media_asset_id uuid NOT NULL,
    role varchar(32) NOT NULL DEFAULT 'PRIMARY',
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (entity_type, entity_id, media_asset_id, role),
    CONSTRAINT ck_media_relation_entity_type CHECK (
        entity_type IN ('CATALOG', 'CATEGORY', 'PRODUCT', 'VARIANT', 'MODIFIER_OPTION')
    ),
    CONSTRAINT ck_media_relation_role CHECK (role IN ('PRIMARY', 'GALLERY', 'THUMBNAIL'))
);

-- Whether a specific location sells a specific variant. This is the only place
-- that answers "can I order this here", and it is separate from the catalog so
-- one location running out of a dish does not edit the brand's menu.
CREATE TABLE catalog.location_offerings (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    variant_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'AVAILABLE',
    fulfillment_modes varchar(128) NOT NULL DEFAULT 'DELIVERY,PICKUP',
    preparation_duration_override interval,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_offering_status CHECK (status IN ('AVAILABLE', 'UNAVAILABLE', 'HIDDEN')),
    -- The composite key is what enforces ADR 0016's rule that a location may
    -- only offer a variant from its own brand.
    CONSTRAINT fk_offering_variant FOREIGN KEY (variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),
    CONSTRAINT uq_offering UNIQUE (location_id, variant_id)
);

CREATE INDEX ix_offerings_by_location
    ON catalog.location_offerings (tenant_id, location_id)
    WHERE status = 'AVAILABLE';

-- An immutable snapshot. The storefront reads only from here, which is the
-- mechanism — not the convention — that stops a draft edit reaching a live menu.
CREATE TABLE catalog.publications (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    catalog_id uuid NOT NULL,
    channel varchar(32) NOT NULL DEFAULT 'STOREFRONT',
    status varchar(16) NOT NULL,
    -- Identifies the snapshot's content. Two publications with the same hash are
    -- the same menu, which is what makes a rollback provably a previous state
    -- rather than a fresh guess at one.
    content_hash varchar(64) NOT NULL,
    validation_report jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    activated_at timestamptz,
    retired_at timestamptz,
    CONSTRAINT ck_publication_status CHECK (
        status IN ('VALIDATING', 'READY', 'REJECTED', 'PUBLISHED', 'RETIRED')
    ),
    CONSTRAINT ck_publication_activated CHECK (
        (status <> 'PUBLISHED') OR (activated_at IS NOT NULL)
    ),
    CONSTRAINT fk_publication_catalog FOREIGN KEY (catalog_id, tenant_id, brand_id)
        REFERENCES catalog.catalogs (id, tenant_id, brand_id)
);

-- At most one live menu per brand and channel. Enforced by the database rather
-- than by the publish transaction, because a race that produced two active
-- publications would be invisible until customers saw different menus.
CREATE UNIQUE INDEX ux_publication_active
    ON catalog.publications (brand_id, channel)
    WHERE status = 'PUBLISHED';

CREATE TABLE catalog.publication_items (
    publication_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    entity_type varchar(32) NOT NULL,
    entity_id uuid NOT NULL,
    entity_version integer NOT NULL,
    -- The whole point of the publication: a copy, not a reference. A later edit
    -- to the authoring row cannot change what was published.
    immutable_content_json jsonb NOT NULL,
    PRIMARY KEY (publication_id, entity_type, entity_id),
    CONSTRAINT fk_publication_item FOREIGN KEY (publication_id)
        REFERENCES catalog.publications (id) ON DELETE CASCADE
);

CREATE INDEX ix_publication_items_lookup
    ON catalog.publication_items (publication_id, entity_type);

GRANT USAGE ON SCHEMA catalog TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA catalog TO qoida_application;

-- Publication items are written once and never edited. The grant says so, so a
-- bug cannot quietly rewrite a published menu.
REVOKE UPDATE, DELETE ON catalog.publication_items FROM qoida_application;
