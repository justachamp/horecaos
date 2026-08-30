package uz.horecaos.platform.payments.settlement;

/** The life of a granted future-discount entitlement. */
public enum EntitlementStatus {

    ACTIVE,

    /** Every granted use has been redeemed. Terminal. */
    EXHAUSTED,

    /**
     * The window closed with uses left.
     *
     * <p>A separate state from {@link #EXHAUSTED} because the two answer different
     * questions about a remedy policy: uses that expired unredeemed are apologies
     * the customer never came back to collect.
     */
    EXPIRED,

    /** Withdrawn before use. */
    REVOKED
}
