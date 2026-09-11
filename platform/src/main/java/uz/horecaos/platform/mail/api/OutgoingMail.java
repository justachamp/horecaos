package uz.horecaos.platform.mail.api;

import java.util.Objects;

/**
 * One email the platform sends (ADR 0097): a single recipient, a subject, a
 * plain-text body and an HTML body carrying the same words.
 *
 * <p>The recipient and both bodies are personal data or carry a one-time
 * link, so nothing here may reach a log: {@link #toString()} names neither.
 */
public record OutgoingMail(String to, String subject, String text, String html) {

    public OutgoingMail {
        Objects.requireNonNull(to, "A recipient is required");
        Objects.requireNonNull(subject, "A subject is required");
        Objects.requireNonNull(text, "A plain-text body is required");
        Objects.requireNonNull(html, "An HTML body is required");
        if (to.isBlank() || to.contains("\n") || to.contains("\r")) {
            throw new IllegalArgumentException("A recipient is one address on one line");
        }
    }

    /** A record's generated {@code toString} would print the address and the link. */
    @Override
    public String toString() {
        return "OutgoingMail[to=<redacted>, subject=<redacted>]";
    }
}
