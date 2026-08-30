package uz.qoida.platform.ordering.domain;

/**
 * What a tenant's outcome reason is for (ADR 0039).
 *
 * <p>Two registries in one table rather than two tables, because they share
 * everything that matters — a tenant name, a platform category, a customer
 * wording, a version — and differ only in which consequence columns apply. The
 * database states that difference as an equivalence per column, so a completion
 * reason cannot carry a stock disposition and a cancellation reason cannot be
 * missing one.
 */
public enum OutcomeReasonKind {

    /** Why an order was cancelled. Carries the stock, liability and refund posture. */
    CANCELLATION,

    /** How an order was completed. Carries the fulfilment modes it is valid for. */
    COMPLETION
}
