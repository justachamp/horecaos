package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The small read {@code marketing} needs about orders that reached the guest late (gap-map
 * row 6.5, the {@code LATE_ORDER_APOLOGY} automation ADR 0044 excluded until ADR 0112
 * decided how it is reconciled with ADR 0013's recovery path).
 *
 * <p>Lateness is derived, never stored: {@code ordering.orders} deliberately has no late
 * column (ADR 0036), so an order is late when it closed more than a margin after the moment
 * it was promised. This port answers with the order id, the number a message may quote and
 * the account to message, and nothing else of the order: no lines, no money, no address, no
 * notes. Those stay inside {@code ordering}, the line {@link OrderDirectory} and {@link
 * AbandonedCartDirectory} already draw.
 */
public interface LateOrderDirectory {

    /**
     * Completed orders of one brand that closed late, inside a window of close times.
     *
     * <p>Only orders that were promised a time (an order with no promise has no lateness to
     * apologise for) and that belong to an account (a guest checkout has no one for
     * marketing to message) are returned. Ordered oldest-close first, so a sweep that is
     * cut off by {@code limit} resumes where it stopped on its next pass rather than always
     * re-reading the newest.
     *
     * @param lateByMinutes how far past the promise the order closed for it to count
     * @param closedAfter exclusive lower bound on the close time
     * @param closedBefore inclusive upper bound on the close time
     */
    List<LateOrder> completedLate(
            UUID tenantId, UUID brandId, int lateByMinutes, Instant closedAfter, Instant closedBefore, int limit);

    /**
     * Whether an ADR 0013 remedy (a refund, a fee reimbursement, a future discount) has been
     * recorded against this order, of any type and in any verification state.
     *
     * <p>This is the reconciliation ADR 0044 demanded before a late-order apology could exist:
     * an order support has already made good is not apologised to a second time by an
     * unattended automation, because "a second compensation path in marketing is how one late
     * delivery gets both a refund from support and a promo code from a trigger with nothing
     * reconciling them". It answers only whether one exists; what the remedy was, what it was
     * worth and how it was attested stay payments' own. It lives on this port, with the order
     * facts, rather than on a payments port so that {@code marketing} reads the order's facts
     * from one module (the dependency would otherwise close a cycle through {@code
     * integration}).
     */
    boolean hasRemedy(UUID tenantId, UUID orderId);

    /**
     * One late order.
     *
     * @param publicOrderNumber the number the guest was given, which is not personal data
     * @param promisedAt when the food was promised
     * @param closedAt when the order completed
     */
    record LateOrder(
            UUID tenantId,
            UUID brandId,
            UUID orderId,
            String publicOrderNumber,
            UUID customerAccountId,
            Instant promisedAt,
            Instant closedAt) {

        /** Whole minutes past the promise, never negative. */
        public long lateByMinutes() {
            return Math.max(0, java.time.Duration.between(promisedAt, closedAt).toMinutes());
        }
    }
}
