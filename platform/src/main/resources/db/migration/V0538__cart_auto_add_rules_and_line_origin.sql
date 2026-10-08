-- ADR 0158 (Accepted 2026-10-07): cart injection, the auto-add rules of gap-map
-- row 4.9.
--
-- A rule is authored in `catalog` (this migration's first half), published with the
-- menu as an `AUTO_ADD_RULE` item (catalog.publication_items.entity_type is a free
-- varchar, so it needs nothing here), and evaluated by the cart in the same
-- transaction as every cart edit. What the evaluator writes is an ordinary cart line
-- with a recorded origin, which is this migration's second half: the origin, the
-- rule id and version on cart and order lines, and the per-cart memory of what a
-- customer removed.
--
-- Three things are deliberately NOT here.
--
--   A way for a rule to name an aggregator or POS channel. The channel list is a
--   CHECK over the cart-built system types only (ADR 0158 Decision 12): an
--   aggregator order arrives with its lines and its total, and a POS order is a
--   ticket the POS keeps editing, so a line added by a rule would change a figure
--   someone else already stated. Only a record that amends 0158 can widen the list.
--
--   A DELETE grant on a rule. A rule is archived, never deleted, so a published
--   snapshot can always be traced to a row.
--
--   A foreign key from an order line to its rule. The origin travels onto the order
--   as evidence; a historical packaging line imported from the legacy platform has
--   origin AUTO_ADD and no rule at all, which only ordering.cart_lines forbids.

-- ----------------------------------------------------------- the rule and its parts

CREATE TABLE catalog.auto_add_rules (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    -- Stable and operator-chosen; the cart line's customer-facing identity is the
    -- rule's translated text, never this code.
    code varchar(64) NOT NULL,
    kind varchar(20) NOT NULL,
    mode varchar(8) NOT NULL DEFAULT 'INJECT',
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    -- PLAIN and PRODUCT_TRIGGERED add one fixed variant; a PORTION_BAND rule takes
    -- the variant from the band that contains the basket's total.
    add_variant_id uuid,
    -- PLAIN: the quantity. PRODUCT_TRIGGERED: the quantity per step.
    quantity numeric(10, 3),
    -- PRODUCT_TRIGGERED: the units of trigger products that make one step.
    trigger_threshold numeric(10, 3),
    repeat_per_multiple boolean NOT NULL DEFAULT false,
    minimum_quantity numeric(10, 3) NOT NULL DEFAULT 1,
    maximum_quantity numeric(10, 3) NOT NULL DEFAULT 20,
    -- Blocked at publication until catalog.auto_add_mandatory_allowed is turned on
    -- by a platform administrator (ADR 0158, first open input). The column exists
    -- so the switch is a policy and not a migration.
    mandatory boolean NOT NULL DEFAULT false,
    fulfillment_modes varchar(16)[] NOT NULL,
    channel_system_types varchar(16)[] NOT NULL,
    -- Display only. No evaluation tie-break exists: two rules do not compete.
    sort_order integer NOT NULL DEFAULT 0,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_auto_add_rule_kind CHECK (kind IN ('PLAIN', 'PRODUCT_TRIGGERED', 'PORTION_BAND')),
    CONSTRAINT ck_auto_add_rule_mode CHECK (mode IN ('INJECT', 'OFFER')),
    CONSTRAINT ck_auto_add_rule_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    -- The kind-specific columns are present exactly when the kind uses them.
    CONSTRAINT ck_auto_add_rule_kind_columns CHECK (
        (kind = 'PLAIN'
            AND add_variant_id IS NOT NULL AND quantity IS NOT NULL AND quantity > 0
            AND trigger_threshold IS NULL AND NOT repeat_per_multiple)
        OR (kind = 'PRODUCT_TRIGGERED'
            AND add_variant_id IS NOT NULL AND quantity IS NOT NULL AND quantity > 0
            AND trigger_threshold IS NOT NULL AND trigger_threshold > 0)
        OR (kind = 'PORTION_BAND'
            AND add_variant_id IS NULL AND quantity IS NULL
            AND trigger_threshold IS NULL AND NOT repeat_per_multiple)),
    -- A mandatory line is never a suggestion: the customer cannot decline an offer
    -- they were never asked to accept.
    CONSTRAINT ck_auto_add_rule_mandatory_injects CHECK (NOT mandatory OR mode = 'INJECT'),
    CONSTRAINT ck_auto_add_rule_bounds CHECK (minimum_quantity > 0 AND maximum_quantity >= minimum_quantity),
    CONSTRAINT ck_auto_add_rule_fulfillment_modes CHECK (
        cardinality(fulfillment_modes) > 0
        AND fulfillment_modes <@ ARRAY['DELIVERY', 'PICKUP', 'DINE_IN']::varchar[]),
    -- AGGREGATOR and POS cannot be stored (Decision 12).
    CONSTRAINT ck_auto_add_rule_channels CHECK (
        cardinality(channel_system_types) > 0
        AND channel_system_types <@ ARRAY[
            'WEB', 'IOS', 'ANDROID', 'TELEGRAM', 'KIOSK', 'QR_TABLE', 'CALL_CENTRE']::varchar[]),
    CONSTRAINT ck_auto_add_rule_version CHECK (version >= 1),
    CONSTRAINT fk_auto_add_rule_variant FOREIGN KEY (add_variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),
    CONSTRAINT uq_auto_add_rule_code UNIQUE (tenant_id, brand_id, code),
    -- The target of the three child tables' composite keys.
    CONSTRAINT uq_auto_add_rule_identity UNIQUE (id, tenant_id, brand_id)
);

CREATE INDEX ix_auto_add_rules_brand ON catalog.auto_add_rules (tenant_id, brand_id, status);

-- PRODUCT_TRIGGERED only: the nodes whose units are counted. Exactly one node each,
-- the pattern the kitchen routing rules use (V0030).
CREATE TABLE catalog.auto_add_rule_triggers (
    id uuid PRIMARY KEY,
    rule_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    variant_id uuid,
    product_id uuid,
    category_id uuid,
    CONSTRAINT ck_auto_add_trigger_one_node CHECK (
        (variant_id IS NOT NULL)::int + (product_id IS NOT NULL)::int + (category_id IS NOT NULL)::int = 1),
    CONSTRAINT fk_auto_add_trigger_rule FOREIGN KEY (rule_id, tenant_id, brand_id)
        REFERENCES catalog.auto_add_rules (id, tenant_id, brand_id),
    CONSTRAINT fk_auto_add_trigger_variant FOREIGN KEY (variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),
    CONSTRAINT fk_auto_add_trigger_product FOREIGN KEY (product_id, tenant_id, brand_id)
        REFERENCES catalog.products (id, tenant_id, brand_id),
    CONSTRAINT fk_auto_add_trigger_category FOREIGN KEY (category_id, tenant_id, brand_id)
        REFERENCES catalog.categories (id, tenant_id, brand_id)
);

CREATE INDEX ix_auto_add_triggers_rule ON catalog.auto_add_rule_triggers (rule_id);

-- PORTION_BAND only. Bands of one rule cannot overlap; btree_gist exists since V0025.
CREATE TABLE catalog.auto_add_rule_bands (
    id uuid PRIMARY KEY,
    rule_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    -- Inclusive.
    portions_from numeric(10, 3) NOT NULL,
    -- Exclusive; null means no upper bound.
    portions_to numeric(10, 3),
    add_variant_id uuid NOT NULL,
    quantity numeric(10, 3) NOT NULL,
    CONSTRAINT ck_auto_add_band_range CHECK (
        portions_from >= 0 AND (portions_to IS NULL OR portions_to > portions_from)),
    CONSTRAINT ck_auto_add_band_quantity CHECK (quantity > 0),
    CONSTRAINT ex_auto_add_band_overlap EXCLUDE USING gist (
        rule_id WITH =, numrange(portions_from, portions_to) WITH &&),
    CONSTRAINT fk_auto_add_band_rule FOREIGN KEY (rule_id, tenant_id, brand_id)
        REFERENCES catalog.auto_add_rules (id, tenant_id, brand_id),
    CONSTRAINT fk_auto_add_band_variant FOREIGN KEY (add_variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id)
);

CREATE INDEX ix_auto_add_bands_rule ON catalog.auto_add_rule_bands (rule_id);

-- Optional. No rows means every location of the brand.
CREATE TABLE catalog.auto_add_rule_locations (
    rule_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    PRIMARY KEY (rule_id, location_id),
    CONSTRAINT fk_auto_add_location_rule FOREIGN KEY (rule_id, tenant_id, brand_id)
        REFERENCES catalog.auto_add_rules (id, tenant_id, brand_id),
    CONSTRAINT fk_auto_add_location_location FOREIGN KEY (tenant_id, location_id)
        REFERENCES tenant.locations (tenant_id, id)
);

-- A rule's customer text ("Cutlery set", shown beside the injected line) is a
-- translation like every other customer-facing catalog string. Widening the list is
-- the move V0443 made for COMBO_GROUP.
ALTER TABLE catalog.translations DROP CONSTRAINT ck_translation_entity_type;

ALTER TABLE catalog.translations ADD CONSTRAINT ck_translation_entity_type CHECK (
    entity_type IN (
        'CATALOG', 'CATEGORY', 'PRODUCT', 'VARIANT', 'MODIFIER_GROUP', 'MODIFIER_OPTION', 'COMBO_GROUP',
        'AUTO_ADD_RULE'
    )
);

-- ----------------------------------------------------- what one unit is worth in portions
--
-- ADR 0137's portion_size is the step a splittable dish is ordered in, not the number
-- of servings one unit holds: a family pizza and a can of cola both have none and are
-- worth very different numbers of portions. Blank means "not counted" and counts as
-- zero. No value is guessed from weight, price or category.
ALTER TABLE catalog.variant_physical_attributes
    ADD COLUMN portions_per_unit numeric(6, 3);

ALTER TABLE catalog.variant_physical_attributes
    ADD CONSTRAINT ck_physical_portions_per_unit CHECK (portions_per_unit IS NULL OR portions_per_unit >= 0);

COMMENT ON COLUMN catalog.variant_physical_attributes.portions_per_unit IS
    'ADR 0158. How many servings one unit of this variant is worth, for PORTION_BAND auto-add rules. Not portion_size (the ordering step). Blank counts as zero.';

-- ------------------------------------------------------------- the cart line's provenance
--
-- An injected line is a cart line with a provenance, not a new kind of thing. Its
-- line_key is 'aa:' plus the rule id (39 characters, inside the 64 line_key allows).
ALTER TABLE ordering.cart_lines
    ADD COLUMN origin varchar(16) NOT NULL DEFAULT 'CUSTOMER',
    ADD COLUMN auto_add_rule_id uuid,
    ADD COLUMN auto_add_rule_version integer,
    -- True once the customer has set this line's quantity themselves, so a later
    -- re-evaluation does not overwrite it (except to enforce the rule's bounds).
    ADD COLUMN customer_adjusted boolean NOT NULL DEFAULT false;

ALTER TABLE ordering.cart_lines
    ADD CONSTRAINT ck_cart_line_origin CHECK (origin IN ('CUSTOMER', 'AUTO_ADD', 'OFFER_ACCEPTED')),
    -- A customer's line has no rule and every other line has one: the rule id is
    -- the evidence the order will carry.
    ADD CONSTRAINT ck_cart_line_origin_rule CHECK ((origin = 'CUSTOMER') = (auto_add_rule_id IS NULL)),
    ADD CONSTRAINT ck_cart_line_rule_version CHECK ((auto_add_rule_id IS NULL) = (auto_add_rule_version IS NULL));

-- The order keeps the origin as evidence. Only the cart forbids an AUTO_ADD line
-- without a rule: a packaging line imported from the legacy platform is an
-- ordinary order line with origin AUTO_ADD and a null rule id.
ALTER TABLE ordering.order_lines
    ADD COLUMN origin varchar(16) NOT NULL DEFAULT 'CUSTOMER',
    ADD COLUMN auto_add_rule_id uuid,
    ADD COLUMN auto_add_rule_version integer,
    ADD COLUMN auto_add_mandatory boolean NOT NULL DEFAULT false;

ALTER TABLE ordering.order_lines
    ADD CONSTRAINT ck_order_line_origin CHECK (origin IN ('CUSTOMER', 'AUTO_ADD', 'OFFER_ACCEPTED'));

-- What the customer took off, remembered for the life of the cart: the rule is never
-- evaluated into that cart again. A rule that re-adds what the customer just removed
-- is the failure the whole record is built to prevent.
CREATE TABLE ordering.cart_auto_add_declines (
    cart_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    rule_id uuid NOT NULL,
    declined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (cart_id, rule_id),
    CONSTRAINT fk_cart_auto_add_decline_cart FOREIGN KEY (cart_id, tenant_id)
        REFERENCES ordering.carts (id, tenant_id) ON DELETE CASCADE
);

-- ----------------------------------------------------------------- reporting (ADR 0043)
--
-- Additive: the order's own origin and rule travel into the sales-by-line fact, so a
-- report can say which lines a rule added. Existing rows are customer lines.
ALTER TABLE reporting.fact_order_line
    ADD COLUMN line_origin varchar(16) NOT NULL DEFAULT 'CUSTOMER',
    ADD COLUMN auto_add_rule_id uuid;

-- ------------------------------------------------------------------------------ grants
--
-- A rule is archived, not deleted. Its children carry no status, archive or validity
-- column and a published snapshot already holds its own immutable copy of them, so a
-- child row needs no tombstone and a PUT must be able to remove a trigger, a band or
-- a location.
GRANT SELECT, INSERT, UPDATE ON catalog.auto_add_rules TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.auto_add_rule_triggers TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.auto_add_rule_bands TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.auto_add_rule_locations TO horecaos_application;
GRANT SELECT, INSERT, DELETE ON ordering.cart_auto_add_declines TO horecaos_application;
