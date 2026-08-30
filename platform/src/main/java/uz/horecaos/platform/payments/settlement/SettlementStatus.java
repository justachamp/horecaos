package uz.horecaos.platform.payments.settlement;

/**
 * The life of one order's settlement (ADR 0046).
 *
 * <p>{@link #PARTIALLY_SETTLED} never rests across a checkout boundary. If any
 * tender fails during checkout, every reservation is released and ADR 0019 takes
 * the {@code PAYMENT_FAILED} path. Half-paid is not a state this platform has,
 * and the value exists only for the instant between one tender settling and the
 * next.
 */
public enum SettlementStatus {
    DRAFT,
    PLANNED,
    PARTIALLY_SETTLED,
    SETTLED,
    PARTIALLY_REVERSED,
    REVERSED,
    FAILED
}
