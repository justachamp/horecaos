-- Wave 9 w4-reports-distance-crm (7.1): the business overview's KPI row has
-- no distance tile. The delivery leg's resolved distance is already computed
-- at pricing time -- ADR 0037's DeliveryFeeResolution / ResolvedDeliveryCharge
-- carries distanceMeters/distanceMode, and fulfillment.delivery_plans (V0054)
-- snapshots the same figure onto the plan the order's shipment hangs off of
-- (fk_shipment_plan) -- but nothing copies it onto reporting.fact_order, so
-- there is no per-order distance for the close job to aggregate.
--
-- Nullable, unlike public_order_number's sibling column: a PICKUP or DINE_IN
-- order never had a delivery leg and null is the honest answer, not zero
-- metres. A DELIVERY order with no delivery_plans row (should not happen in
-- steady state, but a row closed before ADR 0037/T11 shipped might have one)
-- reads null here too rather than a fabricated distance.
--
-- Backfill not required (wave brief): a row closed before this migration
-- stays null and is simply absent from delivery_distance.average.v1's own
-- aggregate, the same treatment delivery_fee_som's own backfill gap
-- disclosed on delivery_fee.v1 (V0383) already established as this build's
-- precedent for a closed, non-recomputed fact.
ALTER TABLE reporting.fact_order
    ADD COLUMN delivery_distance_meters integer,
    ADD CONSTRAINT ck_fact_order_delivery_distance CHECK (delivery_distance_meters IS NULL OR delivery_distance_meters >= 0);

COMMENT ON COLUMN reporting.fact_order.delivery_distance_meters IS
    'ADR 0043 (wave 9 w4-reports-distance-crm). Snapshotted at close time from fulfillment.delivery_plans.distance_meters for the order''s own plan (ADR 0037''s resolved delivery charge, pinned at pricing time) -- never a live figure. Null for a non-delivery order, and null (not zero) for a delivery order whose plan carried no distance or that closed before this migration.';

-- No GRANT block: reporting.fact_order already has one from V0031, and a
-- GRANT is per-table, not per-column (V0326's own closing comment already
-- establishes this for the same table).
