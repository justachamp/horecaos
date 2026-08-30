package uz.horecaos.platform.payments.domain;

/**
 * When the money is expected relative to the restaurant's confirmation.
 *
 * <p>Ordering asks payments this rather than deciding it, because the answer
 * depends on the tender and on the channel's payment method, neither of which
 * ordering owns.
 */
public enum CaptureTiming {

    /**
     * The provider must have credited the order before the restaurant is asked to
     * accept it. Anything else lets a kitchen start cooking against a payment that
     * may never arrive.
     */
    BEFORE_CONFIRMATION,

    /** Cash. The order is confirmed first and collected at the door or the counter. */
    ON_HANDOVER;

    public boolean requiredBeforeConfirmation() {
        return this == BEFORE_CONFIRMATION;
    }
}
