-- Row 3.1 (wave 11 w5-fulfillment-destination, ADR 0014, ADR 0029): the
-- dispatch board's non-PII destination label, snapshotted onto the plan at
-- creation time so the board's 10-second poll never decrypts anything to
-- show roughly where an order is going.
--
-- Ordering computes this (DeliveryDestination#maskedLabel: district/zone and
-- street, never a house number, a flat, or a phone -- see that method's own
-- doc) and hands it across DeliveryOrderPort exactly once, at plan creation
-- (DeliveryPlanningService#open); it is never re-derived or edited after
-- that, the same "snapshot, not a live join" contract every other column
-- copied from DeliveryOrderPort.DeliveryOrder onto this table already keeps
-- (customer_delivery_fee_minor, currency -- see V0054's own comment).
--
-- GRANT-neutral: fulfillment.delivery_plans already carries
-- horecaos_application's SELECT/INSERT/UPDATE grant (V0054); a new nullable
-- column needs no grant of its own.
ALTER TABLE fulfillment.delivery_plans
    ADD COLUMN destination_label varchar(255);

COMMENT ON COLUMN fulfillment.delivery_plans.destination_label IS
    'Row 3.1: a non-PII projection of the delivery destination -- district/zone and street, never a house number, a flat, or a phone. Computed once by ordering (DeliveryDestination#maskedLabel) and snapshotted here at plan creation; null for a plan opened before this migration, or for a destination with neither a zone nor a street to show. The full address stays behind the existing audited reveal.';
