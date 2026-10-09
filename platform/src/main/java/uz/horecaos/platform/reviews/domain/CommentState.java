package uz.horecaos.platform.reviews.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Whether a review's comment may be shown on the brand's public page (ADR 0159, Decisions 6 and 7).
 *
 * <p>Only the <em>comment</em> has a state. A rating is never hidden, changed or left out of the
 * average: it comes from a completed order by its own customer and has nothing to moderate.
 *
 * <ul>
 *   <li>{@link #NOT_SHARED}: the customer did not tick the box, or left no comment. Terminal; nothing
 *       moves it, because a moderator cannot publish words their author did not offer.
 *   <li>{@link #PENDING}: ticked, and waiting for a moderator (the default,
 *       {@code feedback.comments_require_approval}).
 *   <li>{@link #PUBLISHED}: ticked and approved (or approval is switched off).
 *   <li>{@link #HIDDEN}: a moderator hid it, the customer withdrew it, or an erasure removed it.
 * </ul>
 */
public enum CommentState {
    NOT_SHARED,
    PENDING,
    PUBLISHED,
    HIDDEN;

    /** The states a moderator may move a comment to from this one (ADR 0159, Decision 7). */
    public Set<CommentState> moderatorTargets() {
        return switch (this) {
            case PENDING -> EnumSet.of(PUBLISHED, HIDDEN);
            case PUBLISHED -> EnumSet.of(HIDDEN);
            case HIDDEN -> EnumSet.of(PUBLISHED);
            case NOT_SHARED -> EnumSet.noneOf(CommentState.class);
        };
    }
}
