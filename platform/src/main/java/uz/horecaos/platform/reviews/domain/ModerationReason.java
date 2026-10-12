package uz.horecaos.platform.reviews.domain;

import java.util.Optional;

/**
 * The closed list of reasons a moderator may give for hiding a comment (ADR 0159, Decision 7).
 *
 * <p>"Negative", "unfair" and "I disagree" are not reasons, and the form offers no free text: a
 * reason is a code, its label is what the audit fact carries as its {@code reason}, and the comment
 * never appears in either. The same list is shown to the customer as the reason code of their own
 * comment.
 *
 * <p>{@link #CUSTOMER_WITHDREW} and {@link #CUSTOMER_ERASED} are system reasons: they are written by
 * the withdrawal and the erasure and are not in the moderator's list. A comment hidden for either
 * cannot be restored by a moderator, because the words were taken back.
 */
public enum ModerationReason {
    ABUSIVE_LANGUAGE("Abusive language", true),
    PERSONAL_DATA_OF_ANOTHER("Personal data of another person", true),
    NAMES_AN_INDIVIDUAL("Names an individual", true),
    SPAM_OR_UNRELATED("Spam or unrelated", true),
    LEGAL_REQUEST("Legal request", true),
    CUSTOMER_WITHDREW("Withdrawn by the customer", false),
    CUSTOMER_ERASED("Erased at the customer's request", false);

    private final String label;
    private final boolean moderatorMayGive;

    ModerationReason(String label, boolean moderatorMayGive) {
        this.label = label;
        this.moderatorMayGive = moderatorMayGive;
    }

    /** The words an audit fact carries as its reason: never typed text, always one of these. */
    public String label() {
        return label;
    }

    public boolean moderatorMayGive() {
        return moderatorMayGive;
    }

    /** Whether a hide for this reason is final: the customer took the words back. */
    public boolean blocksRestore() {
        return this == CUSTOMER_WITHDREW || this == CUSTOMER_ERASED;
    }

    public static Optional<ModerationReason> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
