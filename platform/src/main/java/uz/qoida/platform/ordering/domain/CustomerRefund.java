package uz.qoida.platform.ordering.domain;

/**
 * The default refund posture a cancellation reason carries (ADR 0039, ADR 0013).
 *
 * <p>A posture rather than a refund. Nothing here moves money: ADR 0013 owns the
 * refund, and this is what the cancel dialog tells the operator to expect and
 * what a report groups by.
 */
public enum CustomerRefund {

    FULL,
    NONE,

    /** The operator decides, above a threshold with ADR 0027 approval. */
    DISCRETIONARY
}
