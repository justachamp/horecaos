package uz.horecaos.platform.pricing.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a customer is told about the promotions behind a priced quote (ADR 0140).
 *
 * <p>Deliberately not the promotions themselves. A promotion's name is the
 * operator's own handle for a list and a report ("internal", the console says so),
 * and its code is the operator's stable key, so neither is wording a storefront may
 * print. What a customer can be told without a decision nobody has made is what
 * kind of benefit it was, whether they asked for it by typing a code, and how much
 * it moved the total. A customer-facing title per locale would be a new field on
 * the promotion and is not part of this record.
 *
 * <p>No identifier crosses this boundary: not the promotion's, not the coupon's.
 * Two promotions that do the same thing to the same total are one entry, because
 * "two offers applied" is a fact about the rule set, not about the basket.
 *
 * @param applied       one entry per (source, effect), discounts first
 * @param couponOutcome what became of the code on the cart, or null when the
 *                      cart carries none (always null for an order, which no
 *                      longer has a cart to type a code into)
 */
public record AppliedPromotions(
        List<Applied> applied, @Nullable CouponOutcome couponOutcome) {

    private static final AppliedPromotions NONE = new AppliedPromotions(List.of(), null);

    public AppliedPromotions {
        applied = applied == null ? List.of() : List.copyOf(applied);
    }

    /** Nothing applied and no code on the cart. */
    public static AppliedPromotions none() {
        return NONE;
    }

    /**
     * @param amountMinor always positive: what it took off ({@code DISCOUNT},
     *                    {@code DELIVERY_DISCOUNT}) or added ({@code SURCHARGE}),
     *                    the direction being the effect's
     */
    public record Applied(Source source, Effect effect, long amountMinor) {}

    /** Whether the customer asked for it. */
    public enum Source {
        /** An automatic promotion: nobody typed anything. */
        AUTOMATIC,
        /** A promotion that applies only to a presented code. */
        PROMO_CODE
    }

    /**
     * What it did to the total. Item and order discounts are one effect because
     * the customer reads both as "taken off what I ordered", and together they are
     * exactly the quote's {@code discountMinor}.
     */
    public enum Effect {
        /** Taken off the goods; included in the quote's {@code discountMinor}. */
        DISCOUNT,
        /** Taken off the delivery charge; already reflected in {@code feeMinor}. */
        DELIVERY_DISCOUNT,
        /** Added to the goods by a markup; already reflected in the subtotal. */
        SURCHARGE
    }

    /**
     * Why a presented code did or did not change the price. Needed because the code
     * stays on the cart in every case but one, and a customer who typed it and sees
     * no discount asks why.
     */
    public enum CouponOutcome {
        /** The code's promotion applied. */
        APPLIED,
        /**
         * The code was valid and its conditions held, but the offers that applied
         * without it are worth at least as much to this basket, and a code never
         * combines with an automatic offer.
         */
        OFFERS_ARE_BETTER,
        /** The code is valid but its promotion did not apply to this basket (a condition did not hold). */
        NOT_APPLICABLE,
        /** The code is no longer valid: expired, switched off, exhausted or already used. */
        NOT_VALID
    }
}
