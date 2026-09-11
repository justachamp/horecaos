package uz.horecaos.platform.iam.api.mail;

/**
 * How {@code iam} gets an email to a staff member's own address (ADR 0098).
 *
 * <p>Implemented by the {@code mail} module over ADR 0097's one SMTP
 * connection; see this package's own note for why the dependency runs that way
 * round rather than {@code iam} calling {@code PlatformMailer} directly.
 *
 * <p>Synchronous and outside any transaction: a caller writes what it intends
 * to send first, sends, then records the outcome, so a slow mail server never
 * holds a database lock.
 */
public interface StaffEmailSender {

    Delivery send(StaffEmail email);

    /** Whether a mail server is configured at all, for a screen that says why nothing was sent. */
    boolean configured();

    /**
     * What became of one send. A caller decides from this alone whether to try
     * again: only {@link Status#FAILED} is worth another attempt later.
     *
     * @param code a short, address-free reason, for a status column and a log
     */
    record Delivery(Status status, String code) {

        public static final Delivery SENT = new Delivery(Status.SENT, "SENT");

        public static final Delivery NOT_CONFIGURED = new Delivery(Status.NOT_CONFIGURED, "MAIL_NOT_CONFIGURED");
    }

    enum Status {
        /** The provider accepted the message for delivery. */
        SENT,
        /** No mail server is configured here; nothing was attempted. */
        NOT_CONFIGURED,
        /** The provider refused this message for good, typically the address. */
        REJECTED,
        /** The provider could not be reached or refused for now; worth retrying. */
        FAILED
    }
}
