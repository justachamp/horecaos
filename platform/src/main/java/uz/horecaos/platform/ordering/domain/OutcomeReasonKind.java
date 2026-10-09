package uz.horecaos.platform.ordering.domain;

/**
 * What a tenant's outcome reason is for (ADR 0039).
 *
 * <p>Three registries in one table rather than three, because they share
 * everything that matters — a tenant name, a platform category, a customer
 * wording, a version — and differ only in which consequence columns apply. The
 * database states that difference per column, so a completion reason cannot carry
 * a stock disposition, a cancellation or removal reason cannot be missing one, and
 * only a cancellation carries a refund posture.
 */
public enum OutcomeReasonKind {

    /** Why an order was cancelled. Carries the stock, liability and refund posture. */
    CANCELLATION,

    /** How an order was completed. Carries the fulfilment modes it is valid for. */
    COMPLETION,

    /**
     * Why a line was taken off a confirmed order, or its quantity lowered (ADR 0169). Carries the stock
     * disposition and the liable party, like a cancellation, and no refund posture: a removal on an order the
     * customer paid through a provider is refused, and a cash order has nothing to refund.
     */
    LINE_REMOVAL
}
