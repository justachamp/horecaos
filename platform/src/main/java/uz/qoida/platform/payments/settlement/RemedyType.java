package uz.qoida.platform.payments.settlement;

/**
 * What kind of remedy was granted (ADR 0013, as amended by the owner's decision
 * of 2026-08-25).
 *
 * <p>Three kinds on one table, because all three are decided by the same person
 * in the same conversation with the same customer and two of them draw on the
 * same tender headroom. What must not follow from sharing a table is a report
 * that adds them up: a refund of goods and a reimbursement of a delivery fee are
 * different money to finance, and a future discount is not money at all until it
 * is redeemed.
 *
 * <p>So the type is never nullable, every aggregate in {@link JdbcRemedyStore}
 * groups by it, and a {@code FUTURE_DISCOUNT} is forbidden by check constraint
 * from carrying a non-zero amount — which means it cannot land in a money total
 * even by a query that forgot to filter.
 */
public enum RemedyType {

    /** Goods, or the order as a whole. Capped by what the tenders settled. */
    ORDER_REFUND,

    /**
     * The delivery fee, in full or in part.
     *
     * <p>Its own type rather than a refund with a note. The fee was charged for a
     * service that failed independently of the food, it is frequently borne by a
     * different party than the goods, and a tenant asking "how much did late
     * delivery cost us last month" is asking for exactly this row and not for the
     * refunds that sit beside it.
     */
    DELIVERY_FEE_REIMBURSEMENT,

    /**
     * An entitlement worth N future uses, against the subtotal, the delivery fee,
     * or both.
     *
     * <p>Costs nothing today and may cost nothing ever. Carries no money columns
     * at all, so it can never be summed into a refund figure; its exposure is on
     * the entitlement row, where it belongs, as uses times the per-use maximum.
     */
    FUTURE_DISCOUNT
}
