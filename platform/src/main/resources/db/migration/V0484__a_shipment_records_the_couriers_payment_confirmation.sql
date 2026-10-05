-- Courier policy 3.9: the post-delivery payment check needs somewhere to record that
-- the courier confirmed the cash/settlement step at the door.
--
-- couriers.md section 16 names the switch "post-delivery payment check": when the
-- tenant turns it on, a delivery may not complete until the courier has confirmed
-- the payment step. Until now the switch was stored and read by nothing, because no
-- courier-facing write existed to enforce it on. The confirmation is a fact about
-- the shipment the courier is carrying, so it lives on that row: when it was made
-- and the amount the courier stated, in minor units with its currency.
--
-- Three columns together or none (ck_shipment_payment_confirmed_triple): a stated
-- amount without a moment or a currency is a number nobody can place.
--
-- No new table, so no new GRANT: fulfillment.shipments already grants the
-- application role SELECT, INSERT and UPDATE (V0054).

ALTER TABLE fulfillment.shipments
    ADD COLUMN payment_confirmed_at timestamptz,
    ADD COLUMN payment_confirmed_minor bigint,
    ADD COLUMN payment_confirmed_currency char(3),
    ADD CONSTRAINT ck_shipment_payment_confirmed_triple CHECK (
        (payment_confirmed_at IS NULL) = (payment_confirmed_minor IS NULL)
        AND (payment_confirmed_at IS NULL) = (payment_confirmed_currency IS NULL)),
    ADD CONSTRAINT ck_shipment_payment_confirmed_amount CHECK (
        payment_confirmed_minor IS NULL OR payment_confirmed_minor >= 0),
    ADD CONSTRAINT ck_shipment_payment_confirmed_currency CHECK (
        payment_confirmed_currency IS NULL OR payment_confirmed_currency ~ '^[A-Z]{3}$');

COMMENT ON COLUMN fulfillment.shipments.payment_confirmed_at IS
    'The moment the courier confirmed the payment step at the door (courier policy postDeliveryPaymentCheckRequired). Null until they do; a delivery that needs the check cannot be marked DELIVERED while it is null and cash is due.';
COMMENT ON COLUMN fulfillment.shipments.payment_confirmed_minor IS
    'The cash the courier stated they collected, in minor units. Zero for an order with nothing due in cash (a prepaid order). The platform requires it to equal what the settlement says is due; a mismatch is refused and nothing is stored.';
