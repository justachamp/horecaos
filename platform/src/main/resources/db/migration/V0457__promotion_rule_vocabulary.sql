-- ADR 0140 (Accepted 2026-10-01): the automatic discount and markup rule engine.
--
-- Extends the rule model V0093 already built; it does not replace it. A
-- promotion stays a `pricing.promotions` row with AND-only conditions and typed
-- actions in a closed vocabulary, and a promo code stays the coupon-gated face
-- of one. V0093 is not edited: its two CHECK constraints are dropped and
-- re-added with the larger vocabulary, which is what "every addition is a
-- migration, a code branch and a test together" means in practice.
--
-- What is NOT here, on purpose:
--   * ORDER_PERCENTAGE_MARKUP / ORDER_FIXED_MARKUP. An order-level markup is a
--     service line the fiscal and tax treatment of which is an open finance and
--     legal input on the record; the action types are absent from the CHECK so
--     that no row can carry one until that answer exists.
--   * A named-customer condition. It waits on a static audience (ADR 0044).

ALTER TABLE pricing.promotions
    ADD COLUMN kind varchar(8) NOT NULL DEFAULT 'DISCOUNT',
    -- Per-promotion usage limits for an automatic promotion. A coupon-gated
    -- promotion carries its limits on the coupon (V0093); the CHECK below refuses
    -- limits on both.
    ADD COLUMN maximum_redemptions integer,
    ADD COLUMN maximum_per_customer integer,
    ADD COLUMN consumed_count integer NOT NULL DEFAULT 0,
    -- Loyalty compatibility is two booleans, not arithmetic (ADR 0140): the most
    -- restrictive value across the applied promotions wins.
    ADD COLUMN loyalty_accrual varchar(8) NOT NULL DEFAULT 'ACCRUE',
    ADD COLUMN loyalty_redemption varchar(8) NOT NULL DEFAULT 'ALLOW',
    -- Who activated it and under which approval request, when one was needed.
    ADD COLUMN activated_by varchar(255),
    ADD COLUMN approval_id uuid;

ALTER TABLE pricing.promotions
    ADD CONSTRAINT ck_promotion_kind CHECK (kind IN ('DISCOUNT', 'MARKUP')),
    -- A markup lands on items or on the order. Delivery scope is refused
    -- (MARKUP_SCOPE_INVALID), and an exclusive markup is a contradiction in
    -- terms because exclusivity never suppresses a markup (EXCLUSIVE_WITH_MARKUP).
    ADD CONSTRAINT ck_promotion_markup_scope CHECK (kind = 'DISCOUNT' OR scope IN ('ITEM', 'ORDER')),
    ADD CONSTRAINT ck_promotion_markup_not_exclusive CHECK (kind = 'DISCOUNT' OR NOT exclusive),
    ADD CONSTRAINT ck_promotion_markup_not_coupon CHECK (kind = 'DISCOUNT' OR NOT requires_coupon),
    ADD CONSTRAINT ck_promotion_maximum_redemptions CHECK (
        maximum_redemptions IS NULL OR maximum_redemptions > 0
    ),
    ADD CONSTRAINT ck_promotion_maximum_per_customer CHECK (
        maximum_per_customer IS NULL OR maximum_per_customer > 0
    ),
    -- The global limit as a database rule, not a Java pre-check: under concurrent
    -- checkout the row lock serialises the increment and this constraint refuses
    -- the one that would go over (the same argument V0093 makes for coupons).
    ADD CONSTRAINT ck_promotion_consumed CHECK (
        consumed_count >= 0
        AND (maximum_redemptions IS NULL OR consumed_count <= maximum_redemptions)
    ),
    ADD CONSTRAINT ck_promotion_loyalty_accrual CHECK (loyalty_accrual IN ('ACCRUE', 'SUPPRESS')),
    ADD CONSTRAINT ck_promotion_loyalty_redemption CHECK (loyalty_redemption IN ('ALLOW', 'BLOCK')),
    ADD CONSTRAINT ck_promotion_limits_not_on_coupon CHECK (
        NOT requires_coupon OR (maximum_redemptions IS NULL AND maximum_per_customer IS NULL)
    );

COMMENT ON COLUMN pricing.promotions.kind IS
    'ADR 0140. DISCOUNT gives, MARKUP charges. A markup is a price-plane step before any discount, is never suppressed by exclusivity and is never compared with a discount.';
COMMENT ON COLUMN pricing.promotions.consumed_count IS
    'ADR 0140. Redemptions claimed at checkout for a limited automatic promotion. An amendment never increments it, a cancellation never decrements it (ADR 0072''s rule for coupons, kept).';

-- ------------------------------------------------------ the closed vocabulary

ALTER TABLE pricing.promotion_conditions
    DROP CONSTRAINT ck_promotion_condition_type;

ALTER TABLE pricing.promotion_conditions
    ADD CONSTRAINT ck_promotion_condition_type CHECK (
        condition_type IN (
            'PRODUCT', 'CATEGORY', 'VARIANT',
            'QUANTITY_AT_LEAST', 'SUBTOTAL_AT_LEAST',
            'CHANNEL', 'LOCATION', 'FULFILLMENT_MODE',
            'DAY_OF_WEEK', 'TIME_OF_DAY',
            'FIRST_ORDER', 'CUSTOMER_SEGMENT',
            -- ADR 0140.
            'PAYMENT_METHOD', 'CHANNEL_TYPE', 'ORDER_SEQUENCE', 'DELIVERY_ZONE'
        )
    );

ALTER TABLE pricing.promotion_actions
    DROP CONSTRAINT ck_promotion_action_type;

ALTER TABLE pricing.promotion_actions
    ADD CONSTRAINT ck_promotion_action_type CHECK (
        action_type IN (
            'ITEM_PERCENTAGE_DISCOUNT', 'ITEM_FIXED_DISCOUNT', 'ITEM_FIXED_PRICE',
            'ORDER_PERCENTAGE_DISCOUNT', 'ORDER_FIXED_DISCOUNT',
            'FREE_DELIVERY', 'REDUCED_DELIVERY',
            'FREE_ITEM',
            -- ADR 0140 stage 2b.
            'ITEM_PERCENTAGE_MARKUP', 'ITEM_FIXED_MARKUP'
        )
    );

-- A quote, and the order that copies it, record a markup as its own adjustment
-- type. Both CHECKs are widened together: CheckoutOrderWriter copies the quote's
-- adjustments verbatim, and V0376 exists because the two were once widened
-- separately and the gap surfaced as a rolled-back checkout.
ALTER TABLE pricing.quote_adjustments
    DROP CONSTRAINT ck_adjustment_type;

ALTER TABLE pricing.quote_adjustments
    ADD CONSTRAINT ck_adjustment_type CHECK (
        adjustment_type IN ('BASE_PRICE', 'MODIFIER', 'ITEM_DISCOUNT', 'ORDER_DISCOUNT',
                            'FEE', 'TAX', 'ROUNDING',
                            'DELIVERY_FEE_WAIVER',
                            'DELIVERY_FEE_BENEFIT',
                            'DELIVERY_TARIFF_DISCOUNT',
                            'ITEM_MARKUP')
    );

ALTER TABLE ordering.order_adjustments
    DROP CONSTRAINT ck_order_adjustment_type;

ALTER TABLE ordering.order_adjustments
    ADD CONSTRAINT ck_order_adjustment_type CHECK (adjustment_type IN (
        'BASE_PRICE', 'MODIFIER', 'ITEM_DISCOUNT', 'ORDER_DISCOUNT', 'FEE', 'TAX', 'ROUNDING',
        'DELIVERY_FEE_WAIVER', 'DELIVERY_FEE_BENEFIT', 'DELIVERY_TARIFF_DISCOUNT',
        'ITEM_MARKUP'));

-- No new GRANT: this widens CHECKs and adds columns on tables that already grant
-- to horecaos_application (V0093, V0019, V0022).
