package uz.horecaos.platform.reviews.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.reviews.domain.CommentState;
import uz.horecaos.platform.reviews.domain.RevealPurpose;

/**
 * The one place a review comment or a case note is encrypted or decrypted (ADR 0159, ADR 0029).
 *
 * <p>Both are {@code PERSONAL}: a customer's words about a real visit can name a server, a courier or
 * another guest, and a staff member's note about a complaint can name the customer. They are
 * envelope-encrypted with a {@link RecordRef} that binds the ciphertext to its own row, and revealed only
 * for one of the four purposes in {@link RevealPurpose}.
 *
 * <p>Centralised so that "who may decrypt a comment, and for what" is a question with one answer. In
 * particular the anonymous public list reveals for {@link RevealPurpose#PUBLIC_DISPLAY} through {@link
 * #revealForPublicDisplay}, which refuses any row that is not {@code PUBLISHED} with the author's consent
 * snapshot true; a test pins that the purpose appears nowhere else.
 */
@Component
public class ReviewCommentVault {

    private static final String REVIEW_TABLE = "reviews.order_reviews";
    private static final String REVIEW_COLUMN = "comment_protected";
    private static final String NOTE_TABLE = "reviews.recovery_case_events";
    private static final String NOTE_COLUMN = "note_protected";

    private final FieldProtection protection;

    public ReviewCommentVault(FieldProtection protection) {
        this.protection = protection;
    }

    /** Encrypts a trimmed, non-blank comment for the review row it will be stored on. */
    public String protectComment(UUID tenantId, UUID reviewId, String plaintext) {
        return protection
                .protect(tenantId, DataClass.PERSONAL, new RecordRef(REVIEW_TABLE, REVIEW_COLUMN, reviewId), plaintext)
                .serialize();
    }

    /** Encrypts a case note for the timeline row it will be stored on. */
    public String protectNote(UUID tenantId, UUID eventId, String plaintext) {
        return protection
                .protect(tenantId, DataClass.PERSONAL, new RecordRef(NOTE_TABLE, NOTE_COLUMN, eventId), plaintext)
                .serialize();
    }

    /**
     * Decrypts a comment for a signed-in reader.
     *
     * @throws IllegalArgumentException for {@link RevealPurpose#PUBLIC_DISPLAY}, which has its own
     *                                  method and its own row check
     */
    public @Nullable String revealComment(
            UUID tenantId, UUID reviewId, @Nullable String commentProtected, RevealPurpose purpose) {
        if (purpose == RevealPurpose.PUBLIC_DISPLAY) {
            throw new IllegalArgumentException("The public reveal goes through revealForPublicDisplay");
        }
        return commentProtected == null
                ? null
                : reveal(tenantId, REVIEW_TABLE, REVIEW_COLUMN, reviewId, commentProtected, purpose);
    }

    /** Decrypts a case note for a signed-in reader. */
    public @Nullable String revealNote(UUID tenantId, UUID eventId, @Nullable String noteProtected) {
        return noteProtected == null
                ? null
                : reveal(tenantId, NOTE_TABLE, NOTE_COLUMN, eventId, noteProtected, RevealPurpose.RECOVERY_CASE_DRAWER);
    }

    /**
     * Decrypts a comment for the anonymous public list: only when the publication row is {@code
     * PUBLISHED} and its consent snapshot is true, and never otherwise.
     *
     * @throws IllegalStateException for any other row state, because reaching here with one is a bug
     *                               in the caller's query, not a customer error to report
     */
    public String revealForPublicDisplay(
            UUID tenantId, UUID reviewId, CommentState state, boolean publicConsent, String commentProtected) {
        if (state != CommentState.PUBLISHED || !publicConsent) {
            throw new IllegalStateException(
                    "A comment is revealed for public display only when it is PUBLISHED with the author's consent");
        }
        return reveal(tenantId, REVIEW_TABLE, REVIEW_COLUMN, reviewId, commentProtected, RevealPurpose.PUBLIC_DISPLAY);
    }

    private String reveal(
            UUID tenantId, String table, String column, UUID recordId, String serialized, RevealPurpose purpose) {
        return protection.reveal(
                tenantId,
                ProtectedValue.deserialize(serialized),
                new RecordRef(table, column, recordId),
                purpose.code());
    }
}
