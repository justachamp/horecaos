-- Row 4.4a (batch 9, wave9-w6-menu-entity): the named `Menu` entity.
--
-- `frontend-information-architecture.md` PART 5 §2 names this as a decision
-- ADR 0016 does not currently make: "A Menu is a first-class named entity
-- between catalog and channel... ADR 0016's brand-catalog/publication/offering
-- model does not currently express it." This migration answers that narrowly,
-- additively, and without touching a single one of ADR 0016's own invariants:
--
--   * catalog.location_offerings is untouched -- still the per-branch,
--     per-variant source of truth it always was, and every existing tenant
--     (nobody has a row in the tables below) keeps reading it exactly as
--     today. See V0390's own header for the opt-in bind step that is the
--     only thing that can ever change that.
--   * catalog.publications/publication_items are untouched -- a publication
--     is still a brand+channel snapshot of the authoring tables, still
--     immutable, still the only thing a customer's client ever reads. A menu
--     is not a second publication mechanism; it is a second, named, copyable
--     *source of per-branch offering data* that CatalogPublicationService's
--     downstream storefront read can consult instead of location_offerings
--     when a branch has opted in (V0390). "Published items" (what a
--     product/category/modifier looks like) is still resolved exactly as
--     ADR 0016 describes; only "does this branch sell this variant, and at
--     what default availability" gains a second, curated, roll-out-able
--     answer.
--
-- A chain with ten branches on one brand today authors location_offerings
-- once per branch, one variant at a time: rolling out a new seasonal
-- assortment is ten operators (or one operator doing it ten times) toggling
-- the same list of dishes over and over, with no record that the ten lists
-- were ever meant to be the same list. catalog.menus is that list, named and
-- versioned once; catalog.menu_items is its membership. V0390 is what lets a
-- branch point at one instead of authoring its own.
--
-- Deliberately excluded from this migration: per-channel *price* (ADR 0018's
-- concern, unchanged), per-item *stock quantity* (row 4.4c, still NOT BUILT,
-- QUANTITY tracking is still refused), and the aggregator preview/projection
-- layer (row 4.6a, still NOT BUILT). This is the entity and its membership,
-- copy, and the filtered-select-all authoring gesture -- the pieces the gap
-- map's own "What is missing" line for 4.4a names as absent, and nothing the
-- IA also flags as needing a decision (fiscal branch scope, the analytics
-- ADR, etc.) that this row does not itself depend on.

CREATE TABLE catalog.menus (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- Operator-facing, e.g. "Основное меню", "Только доставка", "Рамазан".
    -- Unique per brand so a copy cannot silently shadow its source under the
    -- same name -- MenuAuthoringService.copyMenu always mints a distinct one.
    name varchar(255) NOT NULL,

    -- Same three-value domain as every other catalog.md authoring entity
    -- (catalogs, products, categories...). ARCHIVED is the only way to
    -- retire a menu; V0390's foreign key means an archived menu can still be
    -- bound (nothing here refuses it), but MenuAuthoringService.bindToBranch
    -- refuses to bind an ARCHIVED menu going forward -- an operator archiving
    -- a menu that is currently bound is a deliberate two-step (archive does
    -- not auto-unbind, matching catalog.products' own "archived keeps its
    -- placements" precedent).
    status varchar(16) NOT NULL DEFAULT 'DRAFT',

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_menu_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_menu_version CHECK (version >= 1),
    CONSTRAINT uq_menu_name UNIQUE (tenant_id, brand_id, name),
    -- The target of catalog.menu_items' and catalog.branch_menu_bindings'
    -- own composite foreign keys below -- ADR 0016's own "every foreign key
    -- carries tenant_id and brand_id" discipline.
    CONSTRAINT uq_menu_identity UNIQUE (id, tenant_id, brand_id)
);

CREATE INDEX ix_menus_brand ON catalog.menus (tenant_id, brand_id, status);

COMMENT ON TABLE catalog.menus IS
    'Row 4.4a. A named, versioned, brand-owned assortment a chain authors once and can copy or bind to any number of branches (catalog.branch_menu_bindings, V0390) -- additive to ADR 0016''s location_offerings model, never a replacement for it.';

GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.menus TO horecaos_application;

-- ---------------------------------------------------------------------------
-- Membership: which variants a menu carries, and at what default availability
-- ---------------------------------------------------------------------------
CREATE TABLE catalog.menu_items (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    menu_id uuid NOT NULL,
    variant_id uuid NOT NULL,

    sort_order integer NOT NULL DEFAULT 0,

    -- Same domain and same three meanings as
    -- catalog.location_offerings.status (CatalogEntities.OfferingStatus):
    -- AVAILABLE (orderable), UNAVAILABLE (shown, sold out), HIDDEN (not
    -- shown). This is the *default* a bound branch starts at; nothing here
    -- stops a branch operator overriding one item at a time once bound --
    -- that per-branch override is a follow-on this migration does not build,
    -- the same "no per-branch override yet" limitation P45's per-channel
    -- price plane shipped with on its own first cut.
    availability_default varchar(16) NOT NULL DEFAULT 'AVAILABLE',

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_menu_item_availability CHECK (availability_default IN ('AVAILABLE', 'UNAVAILABLE', 'HIDDEN')),
    CONSTRAINT ck_menu_item_version CHECK (version >= 1),
    CONSTRAINT fk_menu_item_menu FOREIGN KEY (menu_id, tenant_id, brand_id)
        REFERENCES catalog.menus (id, tenant_id, brand_id) ON DELETE CASCADE,
    CONSTRAINT fk_menu_item_variant FOREIGN KEY (variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),
    -- A variant is on a menu at most once. Adding an already-present variant
    -- (a single add or the filtered select-all below) is a re-sort/re-default,
    -- never a second row -- the same discipline catalog.product_comment_presets
    -- (V0378) and catalog.product_recommendations (V0302) both already keep.
    CONSTRAINT uq_menu_item UNIQUE (menu_id, variant_id)
);

CREATE INDEX ix_menu_items_menu ON catalog.menu_items (tenant_id, menu_id, sort_order);

COMMENT ON TABLE catalog.menu_items IS
    'Row 4.4a. One variant on one named menu, with the sort position and default availability a branch that binds this menu (V0390) starts from.';

GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.menu_items TO horecaos_application;
