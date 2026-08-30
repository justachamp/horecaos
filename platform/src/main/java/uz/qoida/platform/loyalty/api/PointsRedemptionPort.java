package uz.qoida.platform.loyalty.api;

import java.util.UUID;

/**
 * The only way value leaves a points account (ADR 0046).
 *
 * <p>Four operations, and the shape of the set is the decision. There is no
 * {@code payOut}, no {@code transfer}, and no {@code convert}: a redemption's
 * only sink is a tender on an order belonging to the account's own customer and
 * brand, and every method here is about that one tender. A back door would have
 * to be added to this interface in a reviewed change rather than discovered
 * later in a refund path nobody read as a payments change.
 *
 * <p>Exposed to {@code payments} so that a settlement can plan a balance tender
 * beside its money tenders. The dependency runs one way: this module knows what
 * a tender identifier is and never reads the payments aggregate.
 */
public interface PointsRedemptionPort {

    /**
     * What this customer could redeem against this order, and what they hold.
     *
     * <p>Read-only and holds nothing. The hold happens at
     * {@link #reserve(ReserveCommand)}, because a quote that reserved would let a
     * customer browsing two tabs lock their own balance out of the checkout they
     * actually finish.
     */
    RedemptionOffer quote(RedemptionQuery query);

    /**
     * Debits the points and records the hold against the tender.
     *
     * <p>The hold <em>is</em> the debit. Two carts in two tabs must not both
     * spend the same 40 000, and a counter of held points beside an untouched
     * balance is a read-then-write that both tabs pass. One conditional UPDATE
     * that refuses to take a balance below zero is not.
     *
     * <p>The balance tender reserves before any external tender is initiated,
     * because releasing a points hold is a local write while reversing a captured
     * card payment is a provider refund with an uncertainty window. The other
     * order produces a failed points debit after a successful capture — the case
     * where the customer has paid and the order has not.
     */
    PointsHold reserve(ReserveCommand command);

    /**
     * Marks the hold consumed once its tender settles.
     *
     * <p>Moves no points and takes no actor, because it writes no entry: the
     * points left the balance when the hold was taken, and a second debit here
     * would double-count the redemption on every report that sums the ledger.
     */
    void settle(UUID tenantId, UUID tenderId);

    /**
     * Returns points whose tender never settled, restoring each lot at its
     * original expiry.
     *
     * <p>Points three days from expiry when spent are three days from expiry when
     * returned. Resetting the clock is a quiet giveaway that compounds on every
     * failed checkout.
     */
    void release(UUID tenantId, UUID tenderId, String reasonCode, String actor);

    /**
     * Returns part or all of a settled redemption when the order is refunded.
     *
     * <p>Refunds unwind tenders in reverse order of settlement — external money
     * first, points last — and a partial refund reaches this method only when the
     * money tender is exhausted. Returning points first would leave the customer
     * with points and the tenant with their cash.
     */
    void reverse(UUID tenantId, UUID tenderId, long amountMinor, String reasonCode, String actor);

    /**
     * @param channelCode the order's snapshotted channel code, checked against
     *                    the policy's allowed channels
     */
    record RedemptionQuery(UUID tenantId, UUID brandId, UUID customerAccountId,
            long orderTotalMinor, long deliveryFeeMinor, String currency, String channelCode) {
    }

    /**
     * @param availableMinor  what the account holds, in whole som
     * @param maximumMinor    the largest redemption this order permits, after the
     *                        policy share, the minimum order, and the invariant
     *                        that some money must change hands
     * @param refusal         why the maximum is zero, or null when it is not
     */
    record RedemptionOffer(UUID accountId, long availableMinor, long maximumMinor,
            String currency, String refusal) {

        public boolean redeemable() {
            return maximumMinor > 0;
        }
    }

    /**
     * @param orderId  the order the tender settles. Its {@code customer_account_id}
     *                 and {@code brand_id} must equal the account's, which is
     *                 checked inside the reserving transaction. A guest checkout
     *                 therefore cannot redeem, and an ADR 0039 operator-assisted
     *                 order can redeem only against the identified customer's own
     *                 account. Both consequences are intended
     */
    record ReserveCommand(UUID tenantId, UUID brandId, UUID customerAccountId, UUID orderId,
            UUID tenderId, long amountMinor, String currency, String idempotencyKey,
            String actor) {
    }

    /** @param consumedLots how many lots the hold took, for the receipt of the hold itself */
    record PointsHold(UUID reservationId, UUID accountId, long amountMinor,
            long balanceAfterMinor, int consumedLots) {
    }
}
