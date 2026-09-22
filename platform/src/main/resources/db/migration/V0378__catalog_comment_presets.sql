-- Row 2.1b (batch 8, wave8-w3-kitchen): preset product comments -- a coded
-- kitchen-instruction vocabulary a kitchen line can carry instead of free
-- text, so a KDS renders a coded value rather than a bare flag and a POS
-- export that transports comments as modifiers has something to round-trip
-- through.
--
-- Tenant-scoped rather than brand-scoped, unlike almost every other table in
-- this schema. tenant.sales_channels (V0020) is this migration's precedent
-- for the shape: "without onions" means the same thing at every brand a
-- tenant runs, and a tenant authoring the same fifteen presets once per
-- brand is exactly the duplication a shared, tenant-wide vocabulary avoids.
--
-- This migration is deliberately narrower than gap map row 4.7's own
-- BLOCKED "reference data" bundle (attributes, tags, ingredients, kitchen
-- departments, product comment presets, all one row there): 4.7 is blocked
-- on an unanswered ADR 0016 question -- whether an attribute is the variant
-- axis or a spec-sheet vocabulary -- that a coded kitchen instruction has
-- nothing to do with. Kitchen-department routing already ships by another
-- route entirely (kitchen.brand_routing_rules/location_routing_rules, row
-- 4.2g); this migration is the one other named vocabulary in 4.7's list
-- that does not touch the blocked decision, so it is built on its own.

-- ---------------------------------------------------------------------------
-- The vocabulary itself
-- ---------------------------------------------------------------------------
CREATE TABLE catalog.comment_presets (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- The coded value a line carries and a KDS renders -- operator-typed,
    -- stable, never translated. Unique per tenant so two presets can never
    -- claim the same code and leave a POS export unable to tell them apart.
    code varchar(32) NOT NULL,
    label_ru varchar(120) NOT NULL,
    label_uz varchar(120) NOT NULL,
    label_en varchar(120) NOT NULL,

    -- Where a POS export expects a modifier (R-Keeper comment transport and
    -- its siblings): the coded value this preset round-trips as on that
    -- side. Null is a preset the KDS renders and nothing exports -- the
    -- ordinary state for a tenant with no POS binding yet, or one whose POS
    -- has no modifier of its own for this instruction.
    pos_modifier_code varchar(64),

    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_comment_preset_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_comment_preset_version CHECK (version >= 1),
    CONSTRAINT ck_comment_preset_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT uq_comment_preset_code UNIQUE (tenant_id, code),
    -- The target of catalog.product_comment_presets' own foreign key below.
    CONSTRAINT uq_comment_preset_identity UNIQUE (id, tenant_id)
);

CREATE INDEX ix_comment_presets_tenant ON catalog.comment_presets (tenant_id, sort_order);

COMMENT ON TABLE catalog.comment_presets IS
    'Row 2.1b. A tenant-wide coded kitchen-instruction vocabulary (deliberately no brand_id -- see this migration''s own header). Attached to a product by catalog.product_comment_presets and selected on a line as the coded value a KDS renders and a POS export maps to a modifier via pos_modifier_code.';

GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.comment_presets TO horecaos_application;

-- ---------------------------------------------------------------------------
-- Which presets a product offers
-- ---------------------------------------------------------------------------
-- Row 2.1b's "attachable to a product (which presets apply)". Brand-scoped
-- through the product it attaches to, even though the preset itself is
-- tenant-wide: two brands under one tenant can offer different subsets of
-- the same vocabulary on a dish of the same name. Shaped exactly like
-- catalog.product_recommendations (V0302, row 4.2h) -- the same "attach,
-- detach, or re-sort; never a second row for one pair" discipline.
CREATE TABLE catalog.product_comment_presets (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    product_id uuid NOT NULL,
    preset_id uuid NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_product_comment_preset_version CHECK (version >= 1),
    CONSTRAINT fk_product_comment_preset_product FOREIGN KEY (product_id, tenant_id, brand_id)
        REFERENCES catalog.products (id, tenant_id, brand_id),
    CONSTRAINT fk_product_comment_preset_preset FOREIGN KEY (preset_id, tenant_id)
        REFERENCES catalog.comment_presets (id, tenant_id),
    -- One row per (product, preset): attaching an already-attached preset is
    -- a reorder, never a second row -- the same rule V0302's own
    -- product_recommendations keeps.
    CONSTRAINT uq_product_comment_preset UNIQUE (product_id, preset_id)
);

CREATE INDEX ix_product_comment_presets_product
    ON catalog.product_comment_presets (tenant_id, product_id, sort_order);

COMMENT ON TABLE catalog.product_comment_presets IS
    'Row 2.1b. Which of the tenant''s coded kitchen-instruction presets this product offers on a line, and in what order the picker shows them.';

GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.product_comment_presets TO horecaos_application;
