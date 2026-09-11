package uz.horecaos.platform.mail.api;

/**
 * The platform's one way to send an email (ADR 0097).
 *
 * <p>Synchronous and outside any transaction: a caller writes what it intends
 * to send first, sends, then records the outcome, so a slow mail server never
 * holds a database lock and a crash between the two re-sends rather than
 * losing the message.
 */
public interface PlatformMailer {

    MailOutcome send(OutgoingMail mail);

    /** Whether a mail server is configured, for a screen that says why nothing was sent. */
    boolean configured();
}
