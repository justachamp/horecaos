package uz.qoida.platform.payments.api;

/**
 * What a future-discount entitlement may be applied to (ADR 0013, amended
 * 2026-08-25).
 *
 * <p>Subtotal and delivery fee are separate because they are separately failable
 * and separately borne. A cold pizza is a kitchen failure and the apology belongs
 * against the food; an hour-late courier is a delivery failure and the apology
 * belongs against the fee. {@link #BOTH} exists because a bad enough evening is
 * both, and it is one entitlement rather than two so that one use consumes one
 * use.
 */
public enum EntitlementScope {

    SUBTOTAL,

    DELIVERY_FEE,

    /**
     * Applies to either component, or to both on the same order.
     *
     * <p>Still one use per order. Splitting a {@code BOTH} grant across two orders
     * — subtotal on one, delivery on the next — would turn an N-use grant into a
     * 2N-use grant, which is the arithmetic a customer would find before finance
     * did.
     */
    BOTH;

    public boolean covers(EntitlementScope component) {
        return this == BOTH || this == component;
    }
}
