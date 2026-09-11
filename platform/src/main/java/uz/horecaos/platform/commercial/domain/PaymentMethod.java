package uz.horecaos.platform.commercial.domain;

/**
 * How a tenant is collected for what its wallet does not already cover
 * (ADR 0095).
 */
public enum PaymentMethod {
    /** Waits for a bank transfer that HorecaOS finance records. */
    INVOICE,

    /** Waits for a top-up; nothing collects a remainder automatically. */
    WALLET,

    /**
     * Charged automatically for the remainder once a merchant account
     * exists. Until then a CARD tenant is collected exactly like
     * {@link #INVOICE} — see {@code uz.horecaos.platform.commercial.application.CardCharger}.
     */
    CARD
}
