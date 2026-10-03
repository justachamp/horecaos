-- ADR 0136, the order side: a combo in a cart, a quote, an order, and a hidden or
-- nested modifier on an order line.
--
-- V0443-V0445 built the authoring model and the quote. This migration is the part
-- of the record that says "an order for a combo becomes several ordinary
-- order_lines rows sharing one grouping key -- never a parent line and never a
-- nested payload". Everything below is additive and nullable (or defaulted), so a
-- tenant that sells no combo and attaches no hidden or nested modifier sees no
-- change: no existing row, constraint or reader is touched, and
-- ck_order_total_reconciles needs no change because a combo is arithmetically
-- indistinguishable from its components ordered separately.

-- ------------------------------------------------------------- the quote line
--
-- V0444 gave a quote line the grouping key and the container. An order copies a
-- quote's lines and nothing else, so what an amendment needs in order to price the
-- same combo again has to be on the quote line too: which pairing it was, how many
-- combos the customer bought, and how many times they picked this component.
-- units = combo_quantity x default_quantity x combo_pick_quantity, and the first
-- and the last of those are the customer's choices -- neither can be recovered from
-- the units alone.
ALTER TABLE pricing.quote_lines
    ADD COLUMN combo_component_id uuid,
    ADD COLUMN combo_quantity integer,
    ADD COLUMN combo_pick_quantity integer;

ALTER TABLE pricing.quote_lines
    ADD CONSTRAINT ck_quote_line_combo_provenance CHECK (
        (combo_selection_id IS NULL) = (combo_component_id IS NULL)
        AND (combo_selection_id IS NULL) = (combo_quantity IS NULL)
        AND (combo_selection_id IS NULL) = (combo_pick_quantity IS NULL)
    ),
    ADD CONSTRAINT ck_quote_line_combo_quantities CHECK (
        (combo_quantity IS NULL OR combo_quantity > 0)
        AND (combo_pick_quantity IS NULL OR combo_pick_quantity > 0)
    );

COMMENT ON COLUMN pricing.quote_lines.combo_component_id IS
    'ADR 0136. The catalog.combo_components pairing this line was priced from. The order keeps it so an amendment can price the same combo again.';
COMMENT ON COLUMN pricing.quote_lines.combo_quantity IS
    'ADR 0136. How many combos the customer bought on the cart line; the same on every component line of one selection.';
COMMENT ON COLUMN pricing.quote_lines.combo_pick_quantity IS
    'ADR 0136. How many times the customer picked this component inside one combo (more than one needs a group that allows it).';

-- ------------------------------------------------------------------ the cart
--
-- What the customer chose inside a combo, and the second-level modifier choices.
-- jsonb for the reason selected_modifier_snapshot is: nothing queries into either,
-- both are read whole when the cart is priced and copied whole onto the order.
ALTER TABLE ordering.cart_lines
    ADD COLUMN combo_picks jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN nested_modifiers jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE ordering.cart_lines
    ADD CONSTRAINT ck_cart_line_combo_picks_array CHECK (jsonb_typeof(combo_picks) = 'array'),
    ADD CONSTRAINT ck_cart_line_nested_modifiers_array CHECK (jsonb_typeof(nested_modifiers) = 'array');

COMMENT ON COLUMN ordering.cart_lines.combo_picks IS
    'ADR 0136. [{componentId, quantity}] for a combo line, whose variant is the container; empty for every other line.';
COMMENT ON COLUMN ordering.cart_lines.nested_modifiers IS
    'ADR 0136. [{parentOptionId, optionId}] second-level selections, one level and no more; empty for every line without a linked-variant option.';

-- ------------------------------------------------------------ the order line
--
-- The record's two columns, and the four facts an order needs beside them.
--
--   combo_selection_id           groups the components of one combo purchase
--   combo_container_variant_id   which combo this component belongs to
--
-- are the record's. The rest are snapshots the record's principle asks for ("copied
-- rather than referenced": there is no join from an order line back to a catalog
-- row for a republish to change) and that an amendment cannot do without:
--
--   combo_name_snapshot          the container's name as sold. The container is never
--                                a line, so no line carries its name; a receipt header
--                                or a kitchen display would otherwise join the live
--                                catalog and print today's name on last week's order.
--   combo_component_id           the pairing, so repricing after an amendment prices
--                                the same component at the same combo price rather
--                                than at the variant's own price.
--   combo_quantity               combos bought; the same on every component of one
--   combo_pick_quantity          selection (see pricing.quote_lines).
--
-- All six are set together or not at all, and mean nothing on the vast majority of
-- rows, forever -- the record's own accepted cost of an optional feature on a shared
-- table.
ALTER TABLE ordering.order_lines
    ADD COLUMN combo_selection_id uuid,
    ADD COLUMN combo_container_variant_id uuid,
    ADD COLUMN combo_name_snapshot varchar(255),
    ADD COLUMN combo_component_id uuid,
    ADD COLUMN combo_quantity integer,
    ADD COLUMN combo_pick_quantity integer;

ALTER TABLE ordering.order_lines
    ADD CONSTRAINT ck_order_line_combo_pair CHECK (
        (combo_selection_id IS NULL) = (combo_container_variant_id IS NULL)
    ),
    ADD CONSTRAINT ck_order_line_combo_provenance CHECK (
        (combo_selection_id IS NULL) = (combo_name_snapshot IS NULL)
        AND (combo_selection_id IS NULL) = (combo_component_id IS NULL)
        AND (combo_selection_id IS NULL) = (combo_quantity IS NULL)
        AND (combo_selection_id IS NULL) = (combo_pick_quantity IS NULL)
    ),
    ADD CONSTRAINT ck_order_line_combo_quantities CHECK (
        (combo_quantity IS NULL OR combo_quantity > 0)
        AND (combo_pick_quantity IS NULL OR combo_pick_quantity > 0)
    );

-- "How many Комбо №1 were sold" counts distinct combo_selection_id grouped by
-- combo_container_variant_id; the partial index keeps that, and the per-order
-- grouping every display does, off a scan of every non-combo line.
CREATE INDEX ix_order_lines_combo_selection
    ON ordering.order_lines (tenant_id, order_id, combo_selection_id)
    WHERE combo_selection_id IS NOT NULL;

COMMENT ON COLUMN ordering.order_lines.combo_selection_id IS
    'ADR 0136. Groups the component lines of one combo purchase; null on every other line, forever. A report wanting "how many Комбо №1 were sold" counts distinct values grouped by combo_container_variant_id.';
COMMENT ON COLUMN ordering.order_lines.combo_container_variant_id IS
    'ADR 0136. The combo this component line was bought as part of. Display and receipt-header metadata only: the container itself is never a line and never carries an amount.';
COMMENT ON COLUMN ordering.order_lines.combo_name_snapshot IS
    'ADR 0136. The container''s name as it was sold. Copied, never joined: a combo renamed next month does not rename last week''s receipt.';

-- -------------------------------------------------------- the order modifiers
--
--   parent_order_line_modifier_id   a selection made because the customer picked a
--                                   linked-variant option one level up carries the
--                                   parent selection's id (the record's column).
--                                   Null on every first-level selection.
--   auto_selected                   the server applied this option; the customer never
--                                   chose it. A hidden packaging charge is itemised on
--                                   the order -- "a delivery box always reaches the
--                                   receipt when the order is DELIVERY" -- but it is not
--                                   a choice, so a reorder or an amendment that rebuilds
--                                   the customer's selections must be able to tell it
--                                   from one, or it would be charged twice.
--
-- The parent must be on the same line and in the same tenant, which a three-column
-- reference says and a one-column one cannot; hence the three-column unique it
-- points at (V0046 had to add one the hard way).
ALTER TABLE ordering.order_line_modifiers
    ADD COLUMN parent_order_line_modifier_id uuid,
    ADD COLUMN auto_selected boolean NOT NULL DEFAULT false;

ALTER TABLE ordering.order_line_modifiers
    ADD CONSTRAINT uq_order_modifier_line_identity UNIQUE (id, order_line_id, tenant_id);

ALTER TABLE ordering.order_line_modifiers
    ADD CONSTRAINT fk_order_modifier_parent FOREIGN KEY (parent_order_line_modifier_id, order_line_id, tenant_id)
        REFERENCES ordering.order_line_modifiers (id, order_line_id, tenant_id),
    ADD CONSTRAINT ck_order_modifier_not_own_parent CHECK (
        parent_order_line_modifier_id IS NULL OR parent_order_line_modifier_id <> id
    ),
    -- A nested selection is the customer's second-level choice; the server never
    -- auto-selects one, so the two facts cannot both be true of one row.
    ADD CONSTRAINT ck_order_modifier_nested_is_chosen CHECK (
        parent_order_line_modifier_id IS NULL OR auto_selected = false
    );

CREATE INDEX ix_order_line_modifiers_parent
    ON ordering.order_line_modifiers (parent_order_line_modifier_id)
    WHERE parent_order_line_modifier_id IS NOT NULL;

-- One level of nesting and no more (ADR 0136). A CHECK cannot read another row, so
-- the depth is a trigger: the parent must itself be a first-level selection. The
-- table is insert-only (the application role holds no UPDATE), so a parent's own
-- parent can never be set later and one check at insert time is the whole rule.
CREATE FUNCTION ordering.order_modifier_parent_is_first_level() RETURNS trigger AS $$
BEGIN
    IF NEW.parent_order_line_modifier_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM ordering.order_line_modifiers parent
        WHERE parent.id = NEW.parent_order_line_modifier_id
          AND parent.order_line_id = NEW.order_line_id
          AND parent.tenant_id = NEW.tenant_id
          AND parent.parent_order_line_modifier_id IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'modifier % would be a third level; one level of nesting is supported', NEW.id
            USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_order_modifier_nesting_depth';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_order_modifier_parent_is_first_level
    BEFORE INSERT ON ordering.order_line_modifiers
    FOR EACH ROW EXECUTE FUNCTION ordering.order_modifier_parent_is_first_level();

COMMENT ON COLUMN ordering.order_line_modifiers.parent_order_line_modifier_id IS
    'ADR 0136. The first-level selection whose linked variant offered this one; null on a first-level selection. One level and no more: trg_order_modifier_parent_is_first_level.';
COMMENT ON COLUMN ordering.order_line_modifiers.auto_selected IS
    'ADR 0136. True when the server applied a HIDDEN_AUTO_SELECT option for this order''s fulfilment mode; the customer never chose it, so nothing that rebuilds the customer''s selections may carry it.';

-- No GRANT: every table touched above was granted when it was created, and a
-- table-level privilege covers columns added later. The application role already
-- inserts order lines and modifiers and reads them.
