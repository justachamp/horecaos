package uz.horecaos.platform.pricing.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
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
 * <p>No identifier crosses this boundary in {@code applied}: not the promotion's,
 * not the coupon's. Two promotions that do the same thing to the same total are one
 * entry, because "two offers applied" is a fact about the rule set, not about the
 * basket. The one exception is {@code giftOffers}, which has to name the variant a
 * storefront would add, and carries the rule's id as the opaque key that keeps one
 * offer from being shown twice -- never a name, a code or a coupon.
 *
 * @param applied       one entry per (source, effect), discounts first
 * @param couponOutcome what became of the code on the cart, or null when the
 *                      cart carries none (always null for an order, which no
 *                      longer has a cart to type a code into)
 * @param giftOffers    the gifts a firing {@code FREE_ITEM} rule would price free,
 *                      whether or not the cart holds them yet. An offer, never a
 *                      line: nothing is added to the cart by pricing, and what the
 *                      customer is charged is the next quote's business. Empty for
 *                      an order, which has no cart to add to
 */
public record AppliedPromotions(
        List<Applied> applied, @Nullable CouponOutcome couponOutcome, List<GiftOffer> giftOffers) {

    private static final AppliedPromotions NONE = new AppliedPromotions(List.of(), null, List.of());

    public AppliedPromotions {
        applied = applied == null ? List.of() : List.copyOf(applied);
        giftOffers = giftOffers == null ? List.of() : List.copyOf(giftOffers);
    }

    /** What the customer is told when no gift is on offer: every caller that predates the offers. */
    public AppliedPromotions(List<Applied> applied, @Nullable CouponOutcome couponOutcome) {
        this(applied, couponOutcome, List.of());
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

    /**
     * A free gift the cart could take up.
     *
     * @param ruleId   the rule that would give it; opaque, stable across quotes
     * @param variantId the variant a storefront would add. A rule naming several
     *                 variants yields one offer per variant, any of which fills
     *                 the same allowance
     * @param quantity the units the rule would give free once taken up, across all
     *                 the rule's gift variants
     * @param inCart   whether the cart already holds this variant
     * @param toAdd    the units still missing for the allowance to be complete; zero
     *                 when the cart already holds {@code quantity} units of the
     *                 rule's gift variants, so there is nothing left to offer to add
     */
    public record GiftOffer(UUID ruleId, UUID variantId, BigDecimal quantity, boolean inCart, BigDecimal toAdd) {}

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
