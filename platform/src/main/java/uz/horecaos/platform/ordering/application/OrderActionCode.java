package uz.horecaos.platform.ordering.application;

/**
 * The four lifecycle actions the operations console can ever offer on an order
 * (orders.md §4.2, §4.3).
 *
 * <p>A closed set rather than a free-form string, for the reason {@link
 * uz.horecaos.platform.ordering.domain.OrderStateMachine} is closed: a client that
 * matched on an unlisted code would either crash or silently ignore an action
 * nobody meant to hide.
 */
public enum OrderActionCode {

    /** {@code POST .../approval-decisions} {@code action:APPROVE}. */
    APPROVE,

    /** {@code POST .../approval-decisions} {@code action:REJECT}. */
    REJECT,

    /** {@code POST .../state-actions}, carrying the target status. */
    ADVANCE,

    /** {@code POST .../cancellations}. */
    CANCEL
}
