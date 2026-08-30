package uz.qoida.platform.notifications.domain;

/**
 * Why a message is being sent, which decides what has to be true before it may be
 * (ADR 0020).
 *
 * <p>The distinction between the first two and {@link #MARKETING} is legal rather
 * than tonal. An order confirmation is a receipt for money the customer spent and
 * does not need marketing consent; a promotion does, and sending one under the
 * other's legal basis is the failure this enum exists to make impossible to
 * express.
 *
 * <p>The exact legal basis behind each of these is an open input on ADR 0020 and
 * needs counsel's approval before activation. What this build fixes is the
 * <em>shape</em> — that the purpose is explicit, that consent resolves per
 * purpose, and that the defaults are stated rather than assumed.
 */
public enum NotificationClass {

    /**
     * The customer cannot switch this off. A confirmation, a rejection, a
     * payment failure: the message is part of the transaction rather than an
     * extra the customer opted into.
     */
    TRANSACTIONAL_REQUIRED(false),

    /** Useful but not owed. Requires the template's consent purpose. */
    TRANSACTIONAL_OPTIONAL(true),

    /** Requires consent, always, at the applicable brand and channel scope. */
    MARKETING(true),

    /** Account and credential events. Suppressing one is itself a security risk. */
    SECURITY(false),

    /**
     * Aimed at an on-call route or a shared operations channel, never at a
     * customer. It has no consent to check because there is no data subject in
     * the ADR 0015 sense.
     */
    OPERATIONS_ALERT(false);

    private final boolean requiresConsent;

    NotificationClass(boolean requiresConsent) {
        this.requiresConsent = requiresConsent;
    }

    /** Whether an ADR 0015 decision must exist and be GRANTED before sending. */
    public boolean requiresConsent() {
        return requiresConsent;
    }

    /**
     * Whether a customer's own preference can stop this.
     *
     * <p>The same answer as {@link #requiresConsent} today, and separate anyway:
     * they are different questions with different owners, and folding them into
     * one flag is how a future security alert quietly becomes suppressible.
     */
    public boolean respectsPreference() {
        return requiresConsent;
    }
}
