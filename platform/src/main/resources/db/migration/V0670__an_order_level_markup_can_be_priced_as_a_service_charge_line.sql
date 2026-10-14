-- ADR 0140 Decision ("an ORDER markup is a SERVICE_CHARGE line beside the DELIVERY_FEE line, computed on the
-- post-discount goods subtotal and reported inside fee_minor, recorded as an ORDER_MARKUP adjustment"), gap-map
-- row 6.1 (batch 21, wave w6-promotions-reviews-approvals).
--
-- The engine can now price an order-level markup, and it stays DARK: nothing prices one unless the ADR 0030 key
-- `pricing.order_markup.enabled` is on, and that key stays off until finance and counsel have answered how a
-- service line is taxed and carried on the fiscal receipt (ADR 0140 open input 3; the runbook
-- docs/runbooks/external-connections/w6-promotions-reviews-approvals.md names the question and where the answer
-- goes). This migration only widens the closed vocabularies so the engine's output can be stored when the key is on.
--
-- The three CHECKs of the quote and the one of the order are widened together: CheckoutOrderWriter copies the
-- quote's adjustments verbatim, and V0376 exists because the two were once widened separately and the gap
-- surfaced as a rolled-back checkout.

ALTER TABLE pricing.promotion_actions
    DROP CONSTRAINT ck_promotion_action_type;

ALTER TABLE pricing.promotion_actions
    ADD CONSTRAINT ck_promotion_action_type CHECK (
        action_type IN (
            'ITEM_PERCENTAGE_DISCOUNT', 'ITEM_FIXED_DISCOUNT', 'ITEM_FIXED_PRICE',
            'ORDER_PERCENTAGE_DISCOUNT', 'ORDER_FIXED_DISCOUNT',
            'FREE_DELIVERY', 'REDUCED_DELIVERY',
            'FREE_ITEM',
            'ITEM_PERCENTAGE_MARKUP', 'ITEM_FIXED_MARKUP',
            -- ADR 0140 stage 5: the order-level markup, a service charge.
            'ORDER_PERCENTAGE_MARKUP', 'ORDER_FIXED_MARKUP'
        )
    );

ALTER TABLE pricing.quote_lines
    DROP CONSTRAINT ck_quote_line_type;

ALTER TABLE pricing.quote_lines
    ADD CONSTRAINT ck_quote_line_type CHECK (line_type IN ('ITEM', 'DELIVERY_FEE', 'SERVICE_CHARGE'));

ALTER TABLE pricing.quote_adjustments
    DROP CONSTRAINT ck_adjustment_type;

ALTER TABLE pricing.quote_adjustments
    ADD CONSTRAINT ck_adjustment_type CHECK (
        adjustment_type IN ('BASE_PRICE', 'MODIFIER', 'ITEM_DISCOUNT', 'ORDER_DISCOUNT',
                            'FEE', 'TAX', 'ROUNDING',
                            'DELIVERY_FEE_WAIVER',
                            'DELIVERY_FEE_BENEFIT',
                            'DELIVERY_TARIFF_DISCOUNT',
                            'ITEM_MARKUP',
                            'ORDER_MARKUP')
    );

ALTER TABLE ordering.order_adjustments
    DROP CONSTRAINT ck_order_adjustment_type;

ALTER TABLE ordering.order_adjustments
    ADD CONSTRAINT ck_order_adjustment_type CHECK (adjustment_type IN (
        'BASE_PRICE', 'MODIFIER', 'ITEM_DISCOUNT', 'ORDER_DISCOUNT', 'FEE', 'TAX', 'ROUNDING',
        'DELIVERY_FEE_WAIVER', 'DELIVERY_FEE_BENEFIT', 'DELIVERY_TARIFF_DISCOUNT',
        'ITEM_MARKUP', 'ORDER_MARKUP'));

COMMENT ON COLUMN pricing.quote_lines.line_type IS
    'ADR 0037, ADR 0140. DELIVERY_FEE and SERVICE_CHARGE lines carry no catalogue variant. A SERVICE_CHARGE line is the order-level markup, priced only while pricing.order_markup.enabled is on; its amount is inside fee_minor and carries no tax share until finance and counsel answer how it is taxed.';

-- No new GRANT: this widens CHECKs on tables that already grant to horecaos_application.
