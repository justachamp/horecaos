-- ADR 0140: what a quote has to carry for loyalty to read, and what a cart has
-- to carry for a payment-method promotion to be priceable.
--
-- Loyalty compatibility (two booleans). Pricing records the result of "the most
-- restrictive value across the applied promotions" on the quote; the order
-- snapshot copies it from the accepted quote; `loyalty` reads the order's copy.
-- Defaults are the permissive values, so every quote and order that exists
-- today keeps earning and spending points exactly as it did.

ALTER TABLE pricing.quotes
    ADD COLUMN loyalty_accrual_allowed boolean NOT NULL DEFAULT true,
    ADD COLUMN loyalty_redemption_allowed boolean NOT NULL DEFAULT true;

ALTER TABLE ordering.orders
    ADD COLUMN loyalty_accrual_allowed boolean NOT NULL DEFAULT true,
    ADD COLUMN loyalty_redemption_allowed boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN pricing.quotes.loyalty_accrual_allowed IS
    'ADR 0140. False when any applied promotion carries loyalty_accrual = SUPPRESS: an order carrying it earns no points. Accrual''s base is unchanged (money settled, ADR 0046), which is already net of promotions.';
COMMENT ON COLUMN pricing.quotes.loyalty_redemption_allowed IS
    'ADR 0140. False when any applied promotion carries loyalty_redemption = BLOCK: points cannot be spent on the order.';
COMMENT ON COLUMN ordering.orders.loyalty_accrual_allowed IS
    'ADR 0140. Copied from the accepted quote, the way every other pricing fact of an order is.';
COMMENT ON COLUMN ordering.orders.loyalty_redemption_allowed IS
    'ADR 0140. Copied from the accepted quote.';

-- The payment method as a quote input. A condition on payment method works only
-- if the cart carries the chosen method before it is priced; switching method at
-- checkout then changes the total and forces a re-quote (PRICE_CHANGED), which
-- is the friction the record names. Only the code travels here, never a
-- verdict, for the reason V0171 gives about the applied coupon code.
ALTER TABLE ordering.carts
    ADD COLUMN payment_method_code varchar(32);

ALTER TABLE ordering.carts
    ADD CONSTRAINT ck_cart_payment_method_format
        CHECK (payment_method_code IS NULL OR payment_method_code ~ '^[A-Z0-9_]{2,32}$');

COMMENT ON COLUMN ordering.carts.payment_method_code IS
    'ADR 0140. The money method the customer intends to pay by, or null when none is selected (an unselected method never matches a PAYMENT_METHOD condition). Changing it invalidates the attached quote the same way a line edit does, because it can change what the total will be.';

-- No new GRANT: columns on tables that already grant to horecaos_application.
