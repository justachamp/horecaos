package uz.horecaos.platform.loyalty.domain;

/**
 * How much of an order a redemption may cover (ADR 0046).
 *
 * <p>Two limits with different standing, and the difference matters more than
 * either number. The policy share is a product decision that can be raised to
 * 90% without argument. The requirement that <em>some money changes hands</em>
 * is not: an order with no money tender has no fiscal path at all — no Click
 * payment to hang {@code submit_items} on, no Payme receipt, and on a cash order
 * a courier who collects nothing while handing over food. It also makes the
 * accrual base zero, so the order earns nothing and the customer asks why.
 *
 * <p>So this class caps twice. The share cap is read from policy; the floor of
 * one som of money is applied afterwards and cannot be configured away.
 *
 * <p>Whole som throughout. A basis-point share is computed with integer
 * arithmetic and truncated downwards, because rounding a cap upwards is how a
 * redemption ends up one som over a limit somebody signed off.
 */
public final class RedemptionLimit {

    /** The smallest money tender an order may settle with. */
    private static final long MINIMUM_MONEY_MINOR = 1L;

    private RedemptionLimit() {}

    /**
     * Caps a redemption at the policy share of the eligible value, and then at the
     * hard money floor.
     *
     * @param orderTotalMinor    the whole order, delivery fee included
     * @param deliveryFeeMinor   the fee, which the default policy excludes
     * @param maxShareBasisPoints the policy share of the eligible value
     * @param minOrderMinor      below this order total no redemption is offered
     * @param excludesDeliveryFee whether the fee is outside the eligible value
     * @return the largest amount a redemption may cover, possibly zero
     */
    public static long maximumRedeemable(
            long orderTotalMinor,
            long deliveryFeeMinor,
            int maxShareBasisPoints,
            long minOrderMinor,
            boolean excludesDeliveryFee) {

        if (orderTotalMinor <= 0 || maxShareBasisPoints <= 0) {
            return 0L;
        }
        if (orderTotalMinor < minOrderMinor) {
            // The minimum order stops a small balance from producing a stream of
            // near-free small orders, which is the shape of loyalty abuse that
            // costs a tenant real margin.
            return 0L;
        }

        long eligible = excludesDeliveryFee ? Math.max(0L, orderTotalMinor - deliveryFeeMinor) : orderTotalMinor;

        // Truncated downwards. Rounding a cap upwards is how a redemption ends up
        // one som over a limit somebody signed off.
        long byShare = Math.multiplyExact(eligible, (long) maxShareBasisPoints) / 10_000L;

        // The hard floor. Whatever the policy says, the order keeps at least one
        // som of money on it, because a zero-consideration sale has nowhere to be
        // fiscalized and nothing for a courier to collect.
        long byMoneyFloor = Math.max(0L, orderTotalMinor - MINIMUM_MONEY_MINOR);

        return Math.min(byShare, byMoneyFloor);
    }
}
