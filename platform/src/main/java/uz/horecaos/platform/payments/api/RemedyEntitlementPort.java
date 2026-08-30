package uz.horecaos.platform.payments.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The future-discount entitlements a customer holds, and the single call that
 * spends one (ADR 0013 as amended 2026-08-25, consumed by ADR 0018).
 *
 * <p>Payments owns the grant because the grant is a remedy decided beside a
 * refund, by the same operator, under the same approval threshold. Payments does
 * not own the <em>discount</em>: what a quote is worth after an entitlement is
 * applied is ADR 0018 arithmetic, and computing it here would put a second
 * pricing engine in the payments module.
 *
 * <p>So the seam is deliberately thin. Pricing asks what is available, decides
 * what the discount is worth against its own subtotal and fee, and tells payments
 * how much it actually took. Payments never sees a cart.
 *
 * <p><strong>There is no reservation on this interface, and its absence is a
 * decision.</strong> {@link #redeem} takes the use at order placement, in one
 * conditional UPDATE bounded by {@code uses_consumed &lt; uses_granted}, so two
 * carts racing for the last use produce one winner and one clean refusal rather
 * than two discounts. The cost is that the loser was quoted a price it cannot
 * have, which ADR 0031 already has a shape for — {@code PRICE_CHANGED} — and the
 * benefit is that there is no hold to leak when a checkout is abandoned. A hold
 * is the right answer only if quoting a discount that later evaporates turns out
 * to be common; that is a pricing-side observation, not a payments-side guess.
 */
public interface RemedyEntitlementPort {

    /**
     * What this customer could spend on an order right now.
     *
     * <p>Brand-scoped. An entitlement granted as an apology for one brand's
     * failure is not spendable at another brand of the same tenant, whose legal
     * entity did not agree to it and whose margin would pay for it.
     */
    List<GrantedEntitlement> available(UUID tenantId, UUID brandId, UUID customerAccountId, Instant at);

    /**
     * Spends one use, or refuses.
     *
     * <p>Idempotent per order: a retried placement of the same order sees the
     * redemption already recorded and consumes nothing further.
     */
    RedemptionOutcome redeem(RedeemCommand command);

    /**
     * @param percentBasisPoints null unless the benefit is {@link EntitlementBenefit#PERCENT}
     * @param amountMinor        null unless the benefit is
     *                           {@link EntitlementBenefit#FIXED_AMOUNT}; whole som (ADR 0018)
     * @param maximumMinor       the ceiling for one use. Never null on a percentage
     * @param usesRemaining      granted minus consumed, never negative
     */
    record GrantedEntitlement(
            UUID entitlementId,
            UUID brandId,
            EntitlementScope appliesTo,
            EntitlementBenefit benefit,
            Integer percentBasisPoints,
            Long amountMinor,
            Long maximumMinor,
            String currency,
            int usesRemaining,
            Instant expiresAt) {}

    /**
     * @param subtotalDiscountMinor what pricing took off the subtotal, in whole som
     * @param deliveryDiscountMinor what pricing took off the delivery fee
     */
    record RedeemCommand(
            UUID tenantId,
            UUID entitlementId,
            UUID customerAccountId,
            UUID orderId,
            long subtotalDiscountMinor,
            long deliveryDiscountMinor,
            String currency) {}

    /**
     * @param redeemed        false when the entitlement was exhausted, expired,
     *                        revoked, or is not this customer's
     * @param usesRemaining   after this call. Still meaningful on a refusal that
     *                        was about the amount rather than about the grant: an
     *                        over-cap redemption leaves the uses where they were,
     *                        and reporting zero would tell pricing to stop
     *                        offering an entitlement that is perfectly alive
     * @param refusalCode     null when redeemed; otherwise why, so pricing can
     *                        tell "already used up" from "not yours"
     */
    record RedemptionOutcome(boolean redeemed, int usesRemaining, String refusalCode) {

        public static RedemptionOutcome took(int usesRemaining) {
            return new RedemptionOutcome(true, usesRemaining, null);
        }

        /** For a refusal where there is no grant to report uses against. */
        public static RedemptionOutcome refused(String refusalCode) {
            return new RedemptionOutcome(false, 0, refusalCode);
        }

        public static RedemptionOutcome refused(int usesRemaining, String refusalCode) {
            return new RedemptionOutcome(false, usesRemaining, refusalCode);
        }
    }
}
