-- ADR 0125, open input closed 2026-10-07: reporting.fact_delivery carries brand_id.
--
-- The record asked whether the delivery fact "should eventually carry brand_id once
-- fulfillment.courier_assignment_earnings gains one" and the platform owner accepted the
-- default: it should. The earning still has no brand column, and it does not need one --
-- the close job already reaches past it to fulfillment.assignment_attempts for the one
-- column the earning never carried (accepted_at, V0337), and the shipment it names holds
-- the brand the delivery was made for (V0054: shipments.brand_id, with the composite key
-- (tenant_id, brand_id, location_id) into tenant.locations that makes a location belong
-- to exactly one brand). DayCloseService now reads it from there and writes it here, the
-- way every sibling fact (fact_order, fact_call_hour, fact_promotion_redemption) already
-- carries its brand: a brand-scoped courier report must not have to join back through
-- fulfillment to learn which brand a row is about.
--
-- Rows closed before this migration are filled from the same place the close job now
-- reads. A shipment is required by fk_earning_shipment, so every delivery fact has one;
-- the location is the fallback only so that an impossible orphan fails the NOT NULL below
-- loudly instead of being guessed at.

ALTER TABLE reporting.fact_delivery
    ADD COLUMN brand_id uuid;

UPDATE reporting.fact_delivery fact
   SET brand_id = shipment.brand_id
  FROM fulfillment.shipments shipment
 WHERE shipment.tenant_id = fact.tenant_id
   AND shipment.id = fact.shipment_id
   AND fact.brand_id IS NULL;

UPDATE reporting.fact_delivery fact
   SET brand_id = location.brand_id
  FROM tenant.locations location
 WHERE location.tenant_id = fact.tenant_id
   AND location.id = fact.location_id
   AND fact.brand_id IS NULL;

ALTER TABLE reporting.fact_delivery
    ALTER COLUMN brand_id SET NOT NULL;

COMMENT ON COLUMN reporting.fact_delivery.brand_id IS
    'ADR 0125. The brand the delivery was made for, read at close from the earning''s shipment (fulfillment.shipments.brand_id) -- the earning itself carries none. Derived and rebuildable like every column here.';

-- The brand cut of the courier report, symmetric with ix_fact_delivery_location (V0337).
CREATE INDEX ix_fact_delivery_brand ON reporting.fact_delivery
    (tenant_id, brand_id, business_date);

-- Grants are table-level and already held (V0337); a new column and index change none.
