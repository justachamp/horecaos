package uz.horecaos.platform.commercial.api;

/**
 * An adapter cannot say whether a charge it asked a provider for moved the money (ADR 0095).
 *
 * <p>Thrown out of {@link CardProviderAdapter#charge} and never returned as an outcome, because every
 * outcome the caller can record is final. The caller leaves the attempt {@code PENDING}, writes nothing
 * to the ledger and calls nothing declined; the settlement sweep asks {@link CardProviderAdapter#status}
 * for the same attempt key before it asks again.
 *
 * <p>The message never carries a card reference, a payment identifier or an amount beside a tenant
 * (ADR 0028, ADR 0029): a reason code is all a log line needs.
 */
public class CardChargeUnansweredException extends RuntimeException {

    public CardChargeUnansweredException(String reasonCode) {
        super(reasonCode, null, false, false);
    }
}
