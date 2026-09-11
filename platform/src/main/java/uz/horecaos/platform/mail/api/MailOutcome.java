package uz.horecaos.platform.mail.api;

/**
 * What became of one send (ADR 0097). A caller decides from this alone whether
 * to retry: only {@link Failed} is worth another attempt later.
 */
public sealed interface MailOutcome {

    /** The provider accepted the message for delivery. */
    record Sent() implements MailOutcome {}

    /** No mail server is configured here; nothing was attempted. */
    record NotConfigured() implements MailOutcome {}

    /** The provider refused this message for good, typically the address. */
    record Rejected(String code) implements MailOutcome {}

    /** The provider could not be reached or refused for now; worth retrying. */
    record Failed(String code) implements MailOutcome {}
}
