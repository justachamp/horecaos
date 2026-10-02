-- ADR 0136: the per-variant price map, and the grouping key a quote carries for it.
--
-- pricing.prices gains a fourth priceable_type, COMBO_COMPONENT, whose
-- priceable_id is a catalog.combo_components id. It is a type like the other
-- three, not a parallel pricing path: price-book scope, priority resolution,
-- the close-and-open write discipline (ux_price_current) and the price simulator
-- all apply to it unchanged. priceable_id has never carried a foreign key (it
-- points at a different table per type), so there is nothing to add there;
-- PriceAuthoringService checks the component exists in the brand before a write,
-- exactly as it does for a variant.
ALTER TABLE pricing.prices DROP CONSTRAINT ck_price_type;

ALTER TABLE pricing.prices ADD CONSTRAINT ck_price_type CHECK (
    priceable_type IN ('VARIANT', 'MODIFIER_OPTION', 'FEE', 'COMBO_COMPONENT')
);

-- A priced combo is several ordinary quote lines that share a grouping key. No
-- parent line and no nested payload: a quote total, an order total and every
-- reader that sums lines are correct without learning that a combo exists, which
-- is the whole point of the flat shape (ADR 0136, the ck_order_total_reconciles
-- argument). Only a reader that wants to display the grouping reads these.
--
-- One id per combo purchase -- the cart line the customer added -- and the
-- container variant the components belong to, set together or not at all.
ALTER TABLE pricing.quote_lines
    ADD COLUMN combo_selection_id uuid,
    ADD COLUMN combo_container_variant_id uuid;

ALTER TABLE pricing.quote_lines
    ADD CONSTRAINT ck_quote_line_combo_pair CHECK (
        (combo_selection_id IS NULL) = (combo_container_variant_id IS NULL)
    ),
    -- A delivery fee is not a component of anything.
    ADD CONSTRAINT ck_quote_line_combo_is_item CHECK (
        combo_selection_id IS NULL OR line_type = 'ITEM'
    );

COMMENT ON COLUMN pricing.quote_lines.combo_selection_id IS
    'ADR 0136. Groups the component lines of one combo purchase. Null on every non-combo line, forever. A report wanting "how many Комбо №1 were sold" counts distinct values grouped by combo_container_variant_id.';
COMMENT ON COLUMN pricing.quote_lines.combo_container_variant_id IS
    'ADR 0136. The container variant this component line was bought as part of. Display and receipt-header metadata only: the container itself is never a line and never carries an amount.';
