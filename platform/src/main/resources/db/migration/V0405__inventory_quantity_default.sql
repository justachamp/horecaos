-- ADR 0017's QUANTITY branch (gap map rows 4.4c/4.4d): the first of two
-- migrations for the daily default/auto-reset feature. This one adds the
-- config -- the second stock item's own daily target -- and the next
-- (V0406) adds the state that tracks when it was last applied.
--
-- Open ADR 0017 inputs this wave takes conservatively rather than deciding:
-- negative-stock policy is "never go negative: refuse" (the atomic
-- conditional reservation UPDATE already enforces this; nothing here
-- weakens it), and cancellation restock is "a cancellation releases HELD
-- stock only; committed stock is not restocked" (unchanged -- no new column
-- or code path claims otherwise). Both stay open ADR decisions; this wave
-- only builds what does not require deciding them.

ALTER TABLE inventory.stock_items
    ADD COLUMN default_quantity numeric(14, 3);

ALTER TABLE inventory.stock_items
    ADD CONSTRAINT ck_stock_item_default_quantity CHECK (default_quantity IS NULL OR default_quantity >= 0);

COMMENT ON COLUMN inventory.stock_items.default_quantity IS
    'ADR 0017 QUANTITY branch: the on-hand quantity this item resets to at the tenant''s own business-day boundary (reporting.BusinessDayBoundary). Null means no scheduled reset -- an operator sets on-hand by hand instead. Meaningful only when tracking_mode = QUANTITY; ignored otherwise, the same way every other QUANTITY-only column here is.';
