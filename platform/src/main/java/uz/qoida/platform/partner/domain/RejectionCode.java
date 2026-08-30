package uz.qoida.platform.partner.domain;

/**
 * Why a partner push did not become an order (ADR 0040).
 *
 * <p>Closed and code-owned, like every other vocabulary in this platform that a
 * partner branches on. A rejection is the only thing an aggregator's engineer
 * will ever see of Qoida's internals, so the set has to be small, stable, and
 * actionable from the other side of the integration.
 *
 * <p>What is deliberately <em>not</em> here is a code for an unmapped line. ADR
 * 0040 accepts that order and flags the line, because the alternative is a
 * customer who has already paid the aggregator getting nothing over a menu-sync
 * lag on one item, while the branch never learns why. That is the rule that
 * reverses if the fiscal position ever changes: a receipt needs an ИКПУ per
 * line, so a merchant obliged to fiscalise an aggregator sale cannot accept a
 * line it cannot classify. ADR 0038 settled that the restaurant is the principal
 * and the aggregator discharges the obligation under a recorded contract
 * reference, which is what leaves the tolerance standing.
 */
public enum RejectionCode {

    /**
     * Lines plus fees minus discounts do not equal the stated total. Refused
     * rather than stored, because a booked total that does not equal the sum of
     * its parts is what an accountant finds three months later and nobody can
     * explain — and Qoida validates nothing else about an external total, so
     * this is the whole of what it promises.
     */
    EXTERNAL_TOTAL_MISMATCH,

    /** The venue identifier resolves to no active MARKETPLACE binding. */
    UNKNOWN_VENUE,

    /** The branch is closed beyond the configured grace window. */
    BRANCH_CLOSED,

    /** The partner priced the order in a currency the branch does not sell in. */
    CURRENCY_MISMATCH,

    /**
     * The push carried no lines. Not a validation nicety: an order with no lines
     * reconciles arithmetically at a total of zero, reaches the kitchen, and
     * produces a ticket with nothing on it.
     */
    EMPTY_ORDER,

    /** The partner's credential is not bound to the venue it pushed to. */
    VENUE_NOT_PERMITTED,

    /**
     * The push carries no consideration at all: nothing paid, nothing discounted,
     * every line priced at zero.
     *
     * <p>Not the same as a fully discounted order, which is accepted. A promo code
     * that takes 50 000 som to nothing is a real sale — the promotion is what
     * discharged it, and the settlement is tendered against the promotion so the
     * order stays refundable and remediable like any other. A push where no figure
     * anywhere is above zero is a broken price mapping on the partner's side:
     * V0042's {@code ck_order_settlement_total} can hold no settlement for it, so no
     * remedy could ever be recorded against it, and a branch would find that out the
     * day after it cooked the food. Actionable from the other side of the
     * integration, which is what this vocabulary is for.
     */
    ZERO_VALUE_ORDER
}
