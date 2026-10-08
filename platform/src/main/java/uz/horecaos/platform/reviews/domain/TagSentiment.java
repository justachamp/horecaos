package uz.horecaos.platform.reviews.domain;

/**
 * Whether a feedback tag praises or complains (ADR 0159, Decision 3).
 *
 * <p>A rating of 4-5 offers positive tags, 1-2 negative, and 3 both, so the tag a customer taps
 * never contradicts the number they gave.
 */
public enum TagSentiment {
    POSITIVE,
    NEGATIVE;

    /** The sentiments a customer who gave {@code rating} may be offered, and may attach. */
    public static java.util.Set<TagSentiment> offeredFor(int rating) {
        if (rating >= 4) {
            return java.util.EnumSet.of(POSITIVE);
        }
        if (rating <= 2) {
            return java.util.EnumSet.of(NEGATIVE);
        }
        return java.util.EnumSet.of(POSITIVE, NEGATIVE);
    }
}
