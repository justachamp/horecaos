-- ordering.order_adjustments never learned ADR 0037's three adjustment types.
--
-- V0025 and V0032 each added an adjustment type to `pricing.quote_adjustments`'
-- own CHECK constraint — DELIVERY_FEE_WAIVER (the free-delivery threshold),
-- DELIVERY_FEE_BENEFIT (a promotion's free-delivery grant) and
-- DELIVERY_TARIFF_DISCOUNT (the rate table's own standing discount) — because
-- `PricingEngine.applyDelivery` writes exactly these three beside a delivery
-- fee. `ordering.order_adjustments`' own constraint, `ck_order_adjustment_type`,
-- was never touched by either migration and still lists only the seven values
-- V0022 created it with.
--
-- `CheckoutOrderWriter` copies `quote.adjustments()` onto the order verbatim
-- (`adjustment.adjustmentType()`, unchanged) — see `JdbcOrderStore.insertOrder`
-- — so this was always going to fail the moment a real delivery quote reached
-- checkout with a waiver, a benefit or a tariff discount on it. Nothing had,
-- until CartService started supplying pricing with a destination: every
-- delivery cart before that priced as a collection, so no order's accepted
-- quote had ever carried one of these three adjustment types, and the gap
-- between the two constraints was silent. It is not silent once a delivery
-- order clears a free-delivery threshold or resolves a tariff with its own
-- standing discount — the checkout transaction rolls back on the INSERT below
-- with `ck_order_adjustment_type`, after inventory has already been held and a
-- kitchen slot claimed, for a customer who did nothing wrong.

ALTER TABLE ordering.order_adjustments
    DROP CONSTRAINT ck_order_adjustment_type;

ALTER TABLE ordering.order_adjustments
    ADD CONSTRAINT ck_order_adjustment_type CHECK (adjustment_type IN (
        'BASE_PRICE', 'MODIFIER', 'ITEM_DISCOUNT', 'ORDER_DISCOUNT', 'FEE', 'TAX', 'ROUNDING',
        'DELIVERY_FEE_WAIVER', 'DELIVERY_FEE_BENEFIT', 'DELIVERY_TARIFF_DISCOUNT'));

-- No new GRANT: this widens an existing CHECK constraint on a table
-- ordering.order_adjustments already grants to horecaos_application (V0022).
