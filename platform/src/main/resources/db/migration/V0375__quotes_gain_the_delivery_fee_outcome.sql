-- A delivery quote's fee, without whether it may be trusted (ADR 0037, ADR 0018).
--
-- ---------------------------------------------------------------------------
-- The defect
-- ---------------------------------------------------------------------------
--
-- `pricing.quotes.fee_minor` already exists and already flows onto an order.
-- What it cannot say is *why* it is zero: a collected cart, a delivery cart
-- whose address sits outside every zone, and a delivery cart genuinely inside
-- a free-delivery threshold all write the identical zero to this column.
-- Checkout must refuse the second case and accept the third, and a column that
-- cannot tell them apart cannot be the thing that decides.
--
-- `fulfillment.delivery_fee_resolutions` already records the outcome, but it is
-- fulfillment's own evidence table, keyed to fulfillment's own resolution id —
-- pricing must not join across the module boundary to ask "was my own fee
-- usable", and ordering's checkout, which is the thing that has to ask, is a
-- third module again. The outcome belongs beside the fee it explains.
--
-- ---------------------------------------------------------------------------
-- What these columns are, and are not
-- ---------------------------------------------------------------------------
--
-- `delivery_outcome` mirrors `fulfillment.api.DeliveryFeeOutcome`: null when
-- delivery-fee resolution was never attempted (a collected cart, or a delivery
-- cart with no destination yet), `RESOLVED` or `EXTERNALLY_PRICED` when
-- `fee_minor` is a charge a checkout may accept, and any other value a refusal
-- whose `fee_minor` of zero must not be mistaken for a waiver.
--
-- `delivery_shortfall_minor`, by contrast, can be non-null even when the
-- outcome is `RESOLVED`: ADR 0037's minimum basket is a checkout precondition
-- evaluated in the pricing engine (stage 7), not part of whether the resolver
-- found a zone and a tariff (steps 1-6). A resolved zone can still sit under
-- its own minimum, and checkout must refuse that basket exactly as it refuses
-- an unresolved one.
--
-- `delivery_min_basket_minor` and `delivery_free_from_minor` are carried for
-- display only — "minimum basket 50 000 so'm", "free delivery from 150 000
-- so'm" — and decide nothing; `fee_minor` and `delivery_shortfall_minor`
-- already carry what checkout decides on.

ALTER TABLE pricing.quotes
    ADD COLUMN delivery_outcome varchar(32),
    ADD COLUMN delivery_shortfall_minor bigint,
    ADD COLUMN delivery_min_basket_minor bigint,
    ADD COLUMN delivery_free_from_minor bigint;

ALTER TABLE pricing.quotes
    ADD CONSTRAINT ck_quote_delivery_outcome CHECK (delivery_outcome IN (
        'RESOLVED', 'EXTERNALLY_PRICED', 'LOCATION_NOT_LOCATED', 'OUT_OF_ZONE',
        'OUTSIDE_CATCHMENT', 'NO_TARIFF', 'BEYOND_MAX_DISTANCE'
    )),
    ADD CONSTRAINT ck_quote_delivery_amounts CHECK (
        (delivery_shortfall_minor IS NULL OR delivery_shortfall_minor >= 0)
        AND (delivery_min_basket_minor IS NULL OR delivery_min_basket_minor >= 0)
        AND (delivery_free_from_minor IS NULL OR delivery_free_from_minor >= 0)
    );

-- No new GRANT: V0019 already runs
-- "GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA pricing TO
-- horecaos_application", which covers this table's new columns exactly as it
-- covers its existing ones — a GRANT is per-table, not per-column.
