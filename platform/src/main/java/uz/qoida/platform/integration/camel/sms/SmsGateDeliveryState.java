package uz.qoida.platform.integration.camel.sms;

/**
 * The per-message state {@code /search} reports, from
 * {@code docs/providers/sms-gateway-vas.md}.
 *
 * <p>The same enumeration the inbound delivery callback would carry. It is read
 * here, on a call we make, rather than there: the callback is described as
 * authenticating with {@code login} and {@code key}, and the document's own
 * example shows {@code key} arriving empty, so nothing is built to trust it until
 * a real callback has been observed. See {@code docs/routes/sms-verification.md}.
 *
 * <p><strong>Nothing here concludes "not delivered" from silence.</strong> CDMA
 * subscribers produce no feedback at all, so a message that stays at
 * {@link #SENT} — handed to the operator, never confirmed — is not a failure, and
 * {@link #UNKNOWN} is explicitly terminal-and-unresolved rather than negative.
 * Only the three states the provider states as failures are treated as failures.
 */
enum SmsGateDeliveryState {

    CREATED(0, false),
    SENDING(1, false),

    /** The provider's own failure. */
    FAIL(2, true),

    /** Handed to the operator. Not a confirmation, and not a failure either. */
    SENT(3, false),

    DELIVERED(4, false),

    REJECTED(5, true),

    /**
     * Terminal and unresolved. Not a failure: the gateway accepted the message
     * and cannot say what became of it, which is the ordinary state for a CDMA
     * subscriber and for anyone whose operator sends no receipt.
     */
    UNKNOWN(6, false),

    IN_BLACKLIST(7, true),

    /** A value the document does not list. Treated as accepted-but-unconfirmed. */
    UNRECOGNISED(Integer.MIN_VALUE, false);

    private final int wireValue;
    private final boolean failure;

    SmsGateDeliveryState(int wireValue, boolean failure) {
        this.wireValue = wireValue;
        this.failure = failure;
    }

    static SmsGateDeliveryState of(Integer wireValue) {
        if (wireValue == null) {
            return UNRECOGNISED;
        }
        for (SmsGateDeliveryState state : values()) {
            if (state != UNRECOGNISED && state.wireValue == wireValue) {
                return state;
            }
        }
        return UNRECOGNISED;
    }

    /** Whether the provider has stated that this message will not arrive. */
    boolean isFailure() {
        return failure;
    }

    boolean isBlacklisted() {
        return this == IN_BLACKLIST;
    }
}
