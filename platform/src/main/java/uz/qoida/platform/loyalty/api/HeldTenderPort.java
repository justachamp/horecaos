package uz.qoida.platform.loyalty.api;

import java.util.UUID;

/**
 * Whether the tender a points hold was taken for is still expected to settle
 * (ADR 0046).
 *
 * <p>Declared here and implemented by {@code payments}, the same way ordering
 * declares {@code OrderSettlementPort} and payments implements it. The
 * dependency therefore points the way it already did — payments knows what a
 * points hold is, loyalty does not know what a settlement is — and this module
 * grows no dependency on ordering, whose order statuses it must never learn to
 * read.
 *
 * <h2>Why the sweep has to ask</h2>
 *
 * <p>A hold used to mean one thing: a checkout in flight. Thirty minutes was
 * sized for it, and anything older was an abandoned cart sitting on a customer's
 * balance for an evening. Then the settlement seam was wired into checkout and a
 * hold started meaning a second thing as well: a cash order's balance tender,
 * outstanding until the food is handed over, because a cash tender settles
 * {@code ON_HANDOVER} and the balance tender settles with the money tender it
 * accompanies. A Tashkent delivery order is forty to sixty minutes door to door
 * before an approval deadline or a scheduled pre-order is counted.
 *
 * <p>The two cases are indistinguishable from inside loyalty. A reservation row
 * carries a tender id, an amount and an expiry; nothing on it says whether the
 * order behind it is still coming. So the sweep asks the module that knows, and
 * releases only a hold nobody is waiting on any more.
 *
 * <p>Deliberately one tender at a time rather than a batch. The sweep's batch is
 * bounded at a few hundred rows and the answer is a primary-key read; a bulk
 * shape would trade a readable call site for nothing.
 */
public interface HeldTenderPort {

    /**
     * Whether this tender may still settle, so its hold is not abandoned.
     *
     * <p>True keeps the points held. False releases them, which is what an
     * abandoned checkout has always meant and still means.
     *
     * @param tenderId the tender the hold names. A tender that does not exist —
     *                 a hold taken by a test, or one whose settlement was purged —
     *                 is not awaiting anything, and the answer is false
     */
    boolean stillAwaitingSettlement(UUID tenantId, UUID tenderId);
}
