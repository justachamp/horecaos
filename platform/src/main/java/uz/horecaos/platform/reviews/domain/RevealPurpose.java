package uz.horecaos.platform.reviews.domain;

/**
 * The only reasons a review's comment or a case's note is decrypted (ADR 0159, PII, audit and
 * observability; ADR 0029's named-purpose rule). A closed list in {@code reviews}, not a free string.
 *
 * <p>{@link #PUBLIC_DISPLAY} is the one reveal no signed-in actor performs: the anonymous list decrypts
 * a comment for it, and only for a row that is {@code PUBLISHED} with the customer's consent snapshot
 * true. No other path may use it, and {@code ReviewCommentVault} refuses it for any other row state.
 */
public enum RevealPurpose {
    /** The operations Reviews screen and the moderation queue. */
    OPERATIONS_REVIEWS_SCREEN("reviews.operations.reviews-screen"),
    /** A holder of {@code recovery.case.manage} opening a case, who also holds {@code review.read}. */
    RECOVERY_CASE_DRAWER("reviews.recovery.case-drawer"),
    /** The customer reading their own review back. */
    STOREFRONT_MY_REVIEWS("reviews.storefront.my-reviews"),
    /** The anonymous public list. */
    PUBLIC_DISPLAY("reviews.public.display");

    private final String code;

    RevealPurpose(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
