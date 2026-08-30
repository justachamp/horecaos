package uz.horecaos.platform.payments.domain;

/**
 * How the customer pays (ADR 0013).
 *
 * <p>Cash is a tender and not the absence of one. The legacy {@code
 * payment_methods} table seeds cash enabled and it is this market's majority
 * tender, so a model that treated it as a gap would be modelling most of the
 * traffic as an exception — and, more concretely, would have no place to record
 * that it deliberately carries no fiscal receipt.
 */
public enum PaymentTender {

    CASH,

    PROVIDER;

    public boolean settledByProvider() {
        return this == PROVIDER;
    }
}
