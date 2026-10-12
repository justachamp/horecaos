package uz.horecaos.platform.reviews.domain;

import java.util.Optional;

/**
 * How much of a brand's reviews its public page may show (ADR 0159, Decision 5). Off by default.
 *
 * <p>{@link #RATING_ONLY} shows the count, the average to one decimal and the five-bar distribution;
 * {@link #RATING_AND_COMMENTS} adds the published comments. Nothing a tenant can set filters by
 * rating, pins, reorders, features or replies: the page shows the reviews as they are.
 */
public enum PublicVisibility {
    NONE,
    RATING_ONLY,
    RATING_AND_COMMENTS;

    public boolean showsSummary() {
        return this != NONE;
    }

    public boolean showsComments() {
        return this == RATING_AND_COMMENTS;
    }

    public static Optional<PublicVisibility> parse(String code) {
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
