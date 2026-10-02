-- ADR 0137 (Accepted 2026-10-01): physical and nutritional product attributes,
-- gap-map row 4.2c.
--
-- One optional row per variant carries every fact the record adds -- net weight
-- or net volume, catchweight with its pricing quantum and nominal weight,
-- splittable with a decimal portion size, and КБЖУ per 100 g (or per 100 mL).
-- One table rather than four because the facts are authored on one tab,
-- published as one payload and read together everywhere they are read; none of
-- them is shared across variants the way a modifier group is.
--
-- A row is optional. Most variants (a can of soda, a fixed-recipe burger) carry
-- none of this, and the product editor renders empty fields rather than a row
-- that has to exist in order to be absent -- the same shape
-- catalog.fiscal_classifications already has.
--
-- What is deliberately NOT here:
--
--   The marking exclusion. ADR 0038 forbids a catchweight or splittable variant
--   from also being marking_required, but the two facts live in two tables
--   authored by two screens at two different times, and Postgres cannot express
--   a CHECK across them. CatalogValidator.PHYSICAL_ATTRIBUTES_CONFLICT_WITH_MARKING
--   enforces it at publication, the mechanism every other cross-domain catalog
--   rule already uses, instead of a trigger that could disagree with it.
--
--   A price column. A catchweight variant's price is an ordinary VARIANT row in
--   pricing.prices whose amount_minor means "price per catchweight_quantum_grams"
--   whenever is_catchweight is true (ADR 0137, "Catchweight pricing").
--
--   КБЖУ validation against a reference. The figures are author-typed customer
--   display data; they take part in no constraint, price or fiscal document.
CREATE TABLE catalog.variant_physical_attributes (
    variant_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- Weight and measure. A variant is weighed or measured by volume, never both.
    net_weight_grams integer,
    net_volume_millilitres integer,

    -- Catchweight. The price is per catchweight_quantum_grams; the quote is
    -- provisional against catchweight_nominal_grams (or, failing that, the net
    -- weight) and the charge is reconciled against a weight captured at handover.
    is_catchweight boolean NOT NULL DEFAULT false,
    catchweight_quantum_grams integer,
    catchweight_nominal_grams integer,

    -- Splittable and decimal portions. portion_size is the step a customer may
    -- order in, e.g. 0.5; null means whole units only, exactly as before.
    is_splittable boolean NOT NULL DEFAULT false,
    portion_size numeric(6, 3),

    -- КБЖУ per 100 g, or per 100 mL for a volume-measured variant: the label
    -- convention this market already uses. A portion-scaled figure is a
    -- presentation computation the client makes from these and the variant's own
    -- weight, never a second stored value that could drift from the first.
    calories_kcal_per_100 numeric(6, 1),
    protein_grams_per_100 numeric(5, 2),
    fat_grams_per_100 numeric(5, 2),
    carbohydrates_grams_per_100 numeric(5, 2),

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    -- The tenant and brand are in the key, so a row cannot describe a variant of
    -- another brand or tenant (AGENTS.md: composite keys carry the tenant).
    CONSTRAINT fk_physical_attributes_variant FOREIGN KEY (variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),

    CONSTRAINT ck_physical_weight_xor_volume CHECK (
        net_weight_grams IS NULL OR net_volume_millilitres IS NULL),
    CONSTRAINT ck_catchweight_needs_quantum CHECK (
        NOT is_catchweight OR catchweight_quantum_grams IS NOT NULL),
    CONSTRAINT ck_catchweight_needs_weight CHECK (
        NOT is_catchweight
        OR net_weight_grams IS NOT NULL
        OR catchweight_nominal_grams IS NOT NULL),

    -- Zero and negative are not physical quantities; they are a number field
    -- that was tabbed through and kept a default from somewhere.
    CONSTRAINT ck_physical_positive_measures CHECK (
        (net_weight_grams IS NULL OR net_weight_grams > 0)
        AND (net_volume_millilitres IS NULL OR net_volume_millilitres > 0)
        AND (catchweight_quantum_grams IS NULL OR catchweight_quantum_grams > 0)
        AND (catchweight_nominal_grams IS NULL OR catchweight_nominal_grams > 0)
        AND (portion_size IS NULL OR portion_size > 0)),
    -- Not in ADR 0137's constraint list, added so the database and the domain
    -- object (PhysicalAttributes) refuse the same inputs: a pricing quantum and a
    -- nominal weight describe a catchweight variant and nothing else, and a decimal
    -- portion step is a property of a splittable one. Stored on a variant that is
    -- neither they would be dead figures a reader has to guess the meaning of.
    CONSTRAINT ck_catchweight_facts_need_catchweight CHECK (
        is_catchweight
        OR (catchweight_quantum_grams IS NULL AND catchweight_nominal_grams IS NULL)),
    CONSTRAINT ck_physical_portion_needs_splittable CHECK (
        portion_size IS NULL OR is_splittable),
    CONSTRAINT ck_physical_nutrition_range CHECK (
        (calories_kcal_per_100 IS NULL OR calories_kcal_per_100 >= 0)
        AND (protein_grams_per_100 IS NULL OR protein_grams_per_100 BETWEEN 0 AND 100)
        AND (fat_grams_per_100 IS NULL OR fat_grams_per_100 BETWEEN 0 AND 100)
        AND (carbohydrates_grams_per_100 IS NULL OR carbohydrates_grams_per_100 BETWEEN 0 AND 100)),
    CONSTRAINT ck_physical_version CHECK (version >= 1)
);

CREATE INDEX ix_physical_attributes_brand
    ON catalog.variant_physical_attributes (tenant_id, brand_id);

COMMENT ON TABLE catalog.variant_physical_attributes IS
    'ADR 0137: optional one-to-one physical and nutritional facts of a variant -- weight or volume, catchweight with its pricing quantum, splittable with a decimal portion size, and КБЖУ per 100 g/mL. No row means a fixed unit sold whole.';
COMMENT ON COLUMN catalog.variant_physical_attributes.catchweight_quantum_grams IS
    'ADR 0137: when is_catchweight, the weight pricing.prices.amount_minor is quoted per -- the price is per this many grams, not per unit.';
COMMENT ON COLUMN catalog.variant_physical_attributes.catchweight_nominal_grams IS
    'ADR 0137: the label/menu estimate of one unit''s weight; a quote is provisional against it and the charge is reconciled against the weight captured at handover.';
COMMENT ON COLUMN catalog.variant_physical_attributes.portion_size IS
    'ADR 0137: the step a customer may order this variant in (e.g. 0.5); null means whole units only. Gates the decimal order_lines.quantity.';

-- V0016's blanket grant covered only the tables that existed that day.
GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.variant_physical_attributes TO horecaos_application;
