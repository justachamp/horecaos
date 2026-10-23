package uz.horecaos.platform.mail.api;

/**
 * What became of one send (ADR 0097). A caller decides from this alone whether
 * to retry: only {@link Failed} is worth another attempt later.
 */
public sealed interface MailOutcome {

    /**
     * The {@link Failed} code for a connection that was lost once the message was on its way, when
     * nothing says the server refused it: a read timeout, a reset or a closed line after the data
     * terminator, or a reset mid-session that names no command to place it. The server may have
     * accepted the message. A failure while connecting, greeting or authenticating is never this
     * code, and neither is a server that closed the line before it was given the data: nothing was
     * transmitted, and those are plain retryable failures. A caller that must never send twice (a
     * tenant's campaign email, ADR 0182) treats this one as uncertain and not as a retry; a caller
     * whose message is harmless to repeat (an invitation) may treat it like any other failure.
     */
    String SMTP_NO_ANSWER = "SMTP_NO_ANSWER";

    /** The provider accepted the message for delivery. */
    record Sent() implements MailOutcome {}

    /** No mail server is configured here; nothing was attempted. */
    record NotConfigured() implements MailOutcome {}

    /** The provider refused this message for good, typically the address. */
    record Rejected(String code) implements MailOutcome {}

    /**
     * The provider could not be reached or refused for now; worth retrying, except where the code is
     * {@link #SMTP_NO_ANSWER}, which says the message may already have been accepted.
     */
    record Failed(String code) implements MailOutcome {}
}
