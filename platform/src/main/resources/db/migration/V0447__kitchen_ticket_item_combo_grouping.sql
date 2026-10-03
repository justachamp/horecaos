-- ADR 0136, the kitchen side: which combo a ticket item was bought as part of.
--
-- A combo order is stored as several ordinary order_lines rows sharing one
-- combo_selection_id (V0446), and the kitchen opens one ticket item per order line, routed by
-- that line's own variant -- so a combo's burger goes to the grill and its drink to the bar,
-- exactly as if they had been ordered separately. What the kitchen lacked was the ability to put
-- them back together on a screen: "a combo prints as one header line with each component as a
-- normal, independently routable ticket item".
--
-- The two ids are carried onto the item rather than joined for, for the reason order_line_id
-- and location_id already are: kitchen reads ordering through its port and not through a join, and
-- a display of a live ticket must not depend on ordering's tables answering. They are ids and
-- never names -- ADR 0041 keeps dish names off kitchen rows, and a combo's name is a dish name;
-- the header text is the order line's combo_name_snapshot, read through the order.
--
-- Nullable and unset on every item of every ticket that exists, which is correct: no combo has
-- ever been ordered. Routing does not read either column.
ALTER TABLE kitchen.ticket_items
    ADD COLUMN combo_selection_id uuid,
    ADD COLUMN combo_container_variant_id uuid;

ALTER TABLE kitchen.ticket_items
    ADD CONSTRAINT ck_ticket_item_combo_pair CHECK (
        (combo_selection_id IS NULL) = (combo_container_variant_id IS NULL));

CREATE INDEX ix_ticket_items_combo_selection
    ON kitchen.ticket_items (tenant_id, ticket_id, combo_selection_id)
    WHERE combo_selection_id IS NOT NULL;

COMMENT ON COLUMN kitchen.ticket_items.combo_selection_id IS
    'ADR 0136. Groups the component items of one combo purchase on a screen; the same id as ordering.order_lines.combo_selection_id. An id, never a name (ADR 0041).';
COMMENT ON COLUMN kitchen.ticket_items.combo_container_variant_id IS
    'ADR 0136. The combo this item was bought as part of. Display metadata only: routing resolves from the item''s own variant.';

-- No GRANT: kitchen.ticket_items was granted when it was created, and a table-level privilege
-- covers columns added later.
