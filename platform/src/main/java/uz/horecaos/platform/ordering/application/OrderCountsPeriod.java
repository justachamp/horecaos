package uz.horecaos.platform.ordering.application;

/**
 * Which stretch of time an order-counts read is asked about (IA 0.1a).
 *
 * <p>Two values, and the default is the one that changes nothing. {@code
 * ALL_TIME} is what {@code GET .../orders/counts} answered before a period
 * existed, and stays the default so the order board's tab badges — which read
 * the same endpoint and whose «Все» tab means every order — are unaffected by
 * this wave. The live board asks for {@link #BUSINESS_DAY} explicitly, because
 * «сколько отменили сегодня» is the question it is on the wall to answer.
 *
 * <p>There is deliberately no {@code SHIFT} value. A shift is a real period a
 * supervisor would ask for, and the platform has no shift model for branch staff
 * to hang one on (IA 0.1d and staff-and-access.md §11.1 are the same gap);
 * inventing one here would mean guessing a boundary, and a counter cut at a
 * guessed boundary is worse than one cut at a stated one.
 */
public enum OrderCountsPeriod {

    /** Every order the scope has ever had. The historical columns grow forever. */
    ALL_TIME,

    /**
     * The tenant's current business day (ADR 0043) — not the UTC calendar day.
     *
     * <p>Resolved through {@code BusinessDayWindows}, so a branch that closes at
     * 02:00 files those orders under the trading day they belong to rather than
     * under the following date.
     */
    BUSINESS_DAY
}
