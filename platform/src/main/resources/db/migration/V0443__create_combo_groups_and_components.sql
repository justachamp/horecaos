-- ADR 0136 (Accepted 2026-10-01): composite products. A combo is a product whose
-- sellable variant is a container; what the customer chooses to put in it are
-- ordinary variants, each priced on its own so each can carry its own
-- ИКПУ/MXIK classification onto a receipt line (ADR 0038). This migration is
-- the authoring model only: the two tables below. The price map is a fourth
-- pricing.prices type (V0444), the modifier attachment columns are V0445, and
-- the order-side grouping key arrives with the cart work that writes it.
--
-- Shaped like modifier_groups/modifier_options on purpose. An author who has
-- learned that screen already knows this one, and the capacity rule the
-- validator applies to a modifier group ("choose 3 of 2") generalises to a
-- combo group instead of being written twice.

-- One choice a combo asks the customer to make ("choose a burger", "choose a
-- drink"). A container variant may own several, exactly as a product may have
-- several modifier groups attached.
CREATE TABLE catalog.combo_groups (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    -- The sellable "Комбо №1". Never priced and never sold directly: it exists
    -- so catalog.products/variants has something to name, photograph and
    -- publish, and so a component order line can point back at it.
    container_variant_id uuid NOT NULL,
    code varchar(64) NOT NULL,
    minimum_selections integer NOT NULL DEFAULT 1,
    maximum_selections integer NOT NULL DEFAULT 1,
    allow_same_component_multiple_times boolean NOT NULL DEFAULT false,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_combo_group_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    -- The same unsatisfiable-range refusal as ck_modifier_group_range: a group
    -- whose minimum exceeds its maximum cannot be completed by anyone.
    CONSTRAINT ck_combo_group_range CHECK (
        minimum_selections >= 0
        AND maximum_selections >= 1
        AND minimum_selections <= maximum_selections
    ),
    CONSTRAINT fk_combo_group_container FOREIGN KEY (container_variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),
    CONSTRAINT uq_combo_group_code UNIQUE (tenant_id, brand_id, container_variant_id, code),
    -- The target of fk_combo_component_group below; a three-column reference
    -- needs a three-column unique (V0046 had to add one the hard way).
    CONSTRAINT uq_combo_group_identity UNIQUE (id, tenant_id, brand_id)
);

CREATE INDEX ix_combo_groups_container
    ON catalog.combo_groups (tenant_id, brand_id, container_variant_id);

-- The pairing of a group with a real dish or drink. The price map (V0444) is
-- keyed to this row's id rather than to the variant, because the same drink can
-- sit in two combos at two prices -- "free with the family box", "+3,000 som in
-- the lunch box" -- and a variant-keyed price could not say so.
CREATE TABLE catalog.combo_components (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    combo_group_id uuid NOT NULL,
    component_variant_id uuid NOT NULL,
    -- How many of this variant one choice of it puts on the order. The price
    -- is per unit, so the receipt line reads quantity x unit like any other.
    default_quantity integer NOT NULL DEFAULT 1,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_combo_component_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_combo_component_quantity CHECK (default_quantity BETWEEN 1 AND 100),
    CONSTRAINT fk_combo_component_group FOREIGN KEY (combo_group_id, tenant_id, brand_id)
        REFERENCES catalog.combo_groups (id, tenant_id, brand_id),
    CONSTRAINT fk_combo_component_variant FOREIGN KEY (component_variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),
    -- One pairing per variant per group: offering the same dish twice in one
    -- choice is two prices for one thing.
    CONSTRAINT uq_combo_component_variant UNIQUE (combo_group_id, component_variant_id),
    CONSTRAINT uq_combo_component_identity UNIQUE (id, tenant_id, brand_id)
);

CREATE INDEX ix_combo_components_variant
    ON catalog.combo_components (tenant_id, brand_id, component_variant_id);

-- --------------------------------------------------------------- no nesting
--
-- ADR 0136: a component must not itself be a container ("no nested combos"). A
-- CHECK cannot read another table and a foreign key cannot say "not", so the
-- constraint is a pair of triggers, one on each side of the relation -- adding
-- a component that is a container, and making a container of a variant that is
-- already a component.
--
-- Two sessions can each pass its own check and still produce the forbidden
-- pair (one adds B to a combo of A while the other makes B a container), because
-- neither sees the other's uncommitted row. A transaction-scoped advisory lock
-- on the brand serialises the two triggers: the second waits for the first to
-- commit and its check then runs against a snapshot that includes the row.
-- Status is deliberately ignored: an archived combo is still a combo, and a
-- structural rule that depended on a status flag would let a restore recreate
-- the nesting.

CREATE FUNCTION catalog.combo_component_is_not_a_container() RETURNS trigger AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended('catalog.combo_nesting:' || NEW.tenant_id::text || ':' || NEW.brand_id::text, 0));

    IF EXISTS (
        SELECT 1 FROM catalog.combo_groups g
        WHERE g.tenant_id = NEW.tenant_id
          AND g.brand_id = NEW.brand_id
          AND g.container_variant_id = NEW.component_variant_id
    ) THEN
        RAISE EXCEPTION 'variant % is a combo container and cannot be a component of another combo', NEW.component_variant_id
            USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_combo_no_nesting';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_combo_component_is_not_a_container
    BEFORE INSERT OR UPDATE OF component_variant_id ON catalog.combo_components
    FOR EACH ROW EXECUTE FUNCTION catalog.combo_component_is_not_a_container();

CREATE FUNCTION catalog.combo_container_is_not_a_component() RETURNS trigger AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended('catalog.combo_nesting:' || NEW.tenant_id::text || ':' || NEW.brand_id::text, 0));

    IF EXISTS (
        SELECT 1 FROM catalog.combo_components c
        WHERE c.tenant_id = NEW.tenant_id
          AND c.brand_id = NEW.brand_id
          AND c.component_variant_id = NEW.container_variant_id
    ) THEN
        RAISE EXCEPTION 'variant % is a component of a combo and cannot be a combo container', NEW.container_variant_id
            USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_combo_no_nesting';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_combo_container_is_not_a_component
    BEFORE INSERT OR UPDATE OF container_variant_id ON catalog.combo_groups
    FOR EACH ROW EXECUTE FUNCTION catalog.combo_container_is_not_a_component();

-- ------------------------------------------------------------- translations
--
-- A combo group's heading ("Choose a drink") is customer-facing text, and every
-- other customer-facing catalog string lives in catalog.translations. Widening
-- the entity-type list is the whole change: the table, its tenant-aware primary
-- key (V0077) and its upsert are untouched.
ALTER TABLE catalog.translations DROP CONSTRAINT ck_translation_entity_type;

ALTER TABLE catalog.translations ADD CONSTRAINT ck_translation_entity_type CHECK (
    entity_type IN (
        'CATALOG', 'CATEGORY', 'PRODUCT', 'VARIANT', 'MODIFIER_GROUP', 'MODIFIER_OPTION', 'COMBO_GROUP'
    )
);

COMMENT ON TABLE catalog.combo_groups IS
    'ADR 0136. One choice a combo asks for. The container variant is authoring/display metadata only: it carries no price, tax or classification of its own, and an order for it becomes one ordinary order line per selected component.';
COMMENT ON TABLE catalog.combo_components IS
    'ADR 0136. A real variant offered inside a combo group. Its price is a pricing.prices row of type COMBO_COMPONENT keyed to this id (V0444), never to the variant, so one drink can carry a different price in each combo it sits in. Never a container itself: trg_combo_component_is_not_a_container.';
COMMENT ON COLUMN catalog.combo_components.default_quantity IS
    'Units of the component one choice of it puts on the order. The COMBO_COMPONENT price is per unit.';

-- ------------------------------------------------------------------- grants
--
-- No DELETE: a combo is withdrawn by archiving it, as every other catalog
-- entity is, so a published menu's snapshot can always be traced to a row.
GRANT SELECT, INSERT, UPDATE ON catalog.combo_groups TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON catalog.combo_components TO horecaos_application;
