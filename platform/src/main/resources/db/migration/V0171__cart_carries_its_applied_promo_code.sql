-- ADR 0072: the storefront cart-apply/remove endpoints need somewhere to
-- persist "this cart currently has this code typed in" between requests --
-- a customer who applies a code, closes the app, and comes back should still
-- see it applied.
--
-- Only the raw code travels here, normalized (uppercase, trimmed) by
-- CartService before it is stored -- never the resolved promotion id, never
-- an eligibility verdict. Every consumer (QuoteService at every price,
-- PromoCodeRedemptionPort at checkout) re-resolves the code from
-- pricing.coupon_codes itself against current state, which is the whole
-- point of ADR 0072's "neither check trusts the other's earlier answer": a
-- cached verdict on the cart row would be exactly such a trusted answer.
--
-- No FK to pricing.coupon_codes. A cart that had a valid code applied and
-- then watched it expire or get retired must still show the code it typed --
-- CartService reads the live coupon row (or its absence) to explain the
-- state, not a foreign key that would refuse to store what the customer
-- actually did.

ALTER TABLE ordering.carts
    ADD COLUMN applied_coupon_code varchar(32);

ALTER TABLE ordering.carts
    ADD CONSTRAINT ck_cart_promo_code_format
        CHECK (applied_coupon_code IS NULL OR applied_coupon_code ~ '^[A-Z0-9]{4,32}$');

COMMENT ON COLUMN ordering.carts.applied_coupon_code IS
    'The customer-typed promo code, normalized, or null when none is applied. Survives a line edit -- a code stays applied while the basket changes around it, re-evaluated fresh against the new contents at the next price, exactly like every other pricing input. Only CartService.removePromoCode clears it, and CartService.applyPromoCode replaces it; either call invalidates the attached quote the same way a line edit does, because it changes what the total will be.';
