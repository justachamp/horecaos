package uz.horecaos.platform.mail.api;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One email the platform sends (ADR 0097): a single recipient, a subject, a
 * plain-text body and an HTML body carrying the same words.
 *
 * <p>The recipient and both bodies are personal data or carry a one-time
 * link, so nothing here may reach a log: {@link #toString()} names neither.
 *
 * <p>A message the platform sends about itself (an invitation, a reset) is
 * the four-argument form. A tenant's campaign email (ADR 0182) adds the two
 * things a bulk sender owes the recipient: the name it is sent in, and a
 * one-click way out.
 *
 * @param senderName the name the message is sent in, shown beside the platform's one
 *                   sender address. Null means the address's own configured name. It is
 *                   a tenant's brand name, so it is stripped of control characters and
 *                   quotes and cut short before it reaches a header
 * @param unsubscribeUrl an absolute {@code https} (or, locally, {@code http}) URL that a
 *                       POST of {@code List-Unsubscribe=One-Click} (RFC 8058) unsubscribes
 *                       the recipient from. Null for a message that is not bulk
 */
public record OutgoingMail(
        String to,
        String subject,
        String text,
        String html,
        @Nullable String senderName,
        @Nullable String unsubscribeUrl) {

    /** The longest sender name a header carries. */
    public static final int MAXIMUM_SENDER_NAME = 60;

    public OutgoingMail {
        Objects.requireNonNull(to, "A recipient is required");
        Objects.requireNonNull(subject, "A subject is required");
        Objects.requireNonNull(text, "A plain-text body is required");
        Objects.requireNonNull(html, "An HTML body is required");
        if (to.isBlank() || to.contains("\n") || to.contains("\r")) {
            throw new IllegalArgumentException("A recipient is one address on one line");
        }
        if (unsubscribeUrl != null && !unsubscribeUrl.matches("https?://[^\\s<>\"]+")) {
            throw new IllegalArgumentException("An unsubscribe address is one absolute http(s) URL");
        }
        senderName = cleanName(senderName);
    }

    /** A message the platform sends about itself: no sender name of its own, no unsubscribe. */
    public OutgoingMail(String to, String subject, String text, String html) {
        this(to, subject, text, html, null, null);
    }

    /**
     * A name safe to put in a {@code From} header: control characters, quotes, angle brackets and
     * backslashes removed, whitespace collapsed, at most {@link #MAXIMUM_SENDER_NAME} characters.
     * Blank becomes null, so the configured name stands.
     */
    static @Nullable String cleanName(@Nullable String name) {
        if (name == null) {
            return null;
        }
        String cleaned = name.replaceAll("[\\p{Cntrl}\"<>\\\\]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (cleaned.length() > MAXIMUM_SENDER_NAME) {
            cleaned = cleaned.substring(0, MAXIMUM_SENDER_NAME).strip();
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** A record's generated {@code toString} would print the address and the link. */
    @Override
    public String toString() {
        return "OutgoingMail[to=<redacted>, subject=<redacted>]";
    }
}
