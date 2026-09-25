-- ADR 0017's QUANTITY branch (gap map rows 4.4c/4.4d): the second of two
-- migrations for the daily default/auto-reset feature (V0405 added the
-- config; this adds the state). Lives on inventory.positions rather than
-- inventory.stock_items because it is mutated by every reset, the same
-- table the reset itself writes on_hand_quantity to -- not a second table a
-- reset has to keep in step with the first.

ALTER TABLE inventory.positions
    ADD COLUMN last_reset_business_date date;

COMMENT ON COLUMN inventory.positions.last_reset_business_date IS
    'ADR 0017 QUANTITY branch: the tenant business date (reporting.BusinessDayBoundary) this item''s on-hand quantity was last set to its stock_items.default_quantity by the scheduled reset. Null means never reset by the job -- InventoryQuantityResetScheduler''s own due predicate is "null or before today''s business date", so a freshly listed item resets the first time its business day comes due rather than waiting for a prior date to compare against.';

-- The reset scheduler's own due query filters on tracking_mode, default_quantity
-- and this column together; a partial index on the column alone would not match
-- that predicate, and the table stays small per tenant, so no new index is added.
