package uz.horecaos.platform.notifications.domain;

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

    /**
     * Whether a customer's own quiet-hours window may hold this message rather
     * than send it immediately.
     *
     * <p>The same answer as {@link #respectsPreference} today — a
     * {@code notification_preferences} row (the only place a quiet-hours window
     * lives) can only exist for a class {@code NotificationPreferenceService#set}
     * lets a customer edit, which is exactly {@link #respectsPreference}'s set —
     * and kept as its own named question anyway, for the reason that method's own
     * Javadoc gives: folding two different questions into one flag is how one of
     * them quietly changes meaning when only the other was intended.
     *
     * <p>{@code TRANSACTIONAL_REQUIRED} and {@code SECURITY} answer {@code
     * false} on purpose. ADR 0020: "required transactional and security messages
     * ... still respect channel feasibility and quiet-hour exceptions" — read as
     * required transactional and security messages <em>being</em> the exception,
     * not as a window that can silently sit a customer's own order confirmation,
     * payment failure, or refund notice in a queue for hours while they are
     * waiting on it. {@code OPERATIONS_ALERT} answers {@code false} for the
     * reason {@link #requiresConsent} does: there is no data subject, so there is
     * no preference row and no window to read.
     */
    public boolean respectsQuietHours() {
        return requiresConsent;
    }
}
