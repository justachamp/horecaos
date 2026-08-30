package uz.horecaos.platform.notifications.domain;

/**
 * Where one logical message has got to (ADR 0020).
 *
 * <p>{@link #DELIVERED} means the strongest verified status the provider actually
 * supplied, which for an SMS gateway is usually "accepted for delivery" rather
 * than "the handset showed it". ADR 0020 is explicit that HorecaOS must not promise a
 * stronger guarantee than the provider gave; the exact provider wording is kept
 * verbatim on the status event so a support conversation can turn on it.
 */
public enum NotificationStatus {

    /** The intent exists and commits with the business fact that caused it. */
    CREATED,

    /** Eligible, addressed, and bound to a template version. Awaiting dispatch. */
    READY,

    /** A provider request is in flight under a claim held by one node. */
    SENDING,

    /** A known failure that changes on its own. Backed off, then tried again. */
    RETRY_PENDING,

    /**
     * The provider may or may not have acted.
     *
     * <p>The status that matters most. It is not a failure to retry: retrying
     * blindly is how one confirmation becomes two, so it reconciles first.
     */
    UNCERTAIN,

    /** Discovering the truth after an uncertain outcome, by asking the provider. */
    RECONCILING,

    /** Accepted or better, as verified. Terminal. */
    DELIVERED,

    /** Refused in a way that will not change. Terminal. */
    FAILED_TERMINAL,

    /** Never sent, and the row says why. Terminal. */
    SUPPRESSED,

    /** Too late to be worth sending. Terminal. */
    EXPIRED,

    /** Automation is out of safe moves. A person decides. Terminal until they do. */
    MANUAL_REVIEW;

    public boolean isTerminal() {
        return this == DELIVERED
                || this == FAILED_TERMINAL
                || this == SUPPRESSED
                || this == EXPIRED
                || this == MANUAL_REVIEW;
    }
}
