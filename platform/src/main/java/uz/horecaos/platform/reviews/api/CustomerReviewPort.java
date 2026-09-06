package uz.horecaos.platform.reviews.api;

import java.util.UUID;

/**
 * A customer rating their own completed order, for a consumer outside
 * {@code reviews} (ADR 0075's chat rating prompt).
 *
 * <p>Narrow by design, in the genre of {@code StockAvailabilityPort}: one
 * write and one question, and nothing about listing, moderating or reading a
 * comment. It exists because {@code integration}'s Telegram handler cannot
 * reach {@code reviews.application} under Spring Modulith.
 *
 * <p>No comment field. A star rating is a tap; a comment is typing, and a bot
 * collecting free text would need a conversation state machine, ADR 0059's
 * flows, and somewhere to put an ADR 0029 personal string that arrived over a
 * channel the customer may be sharing a screen of. The comment stays where
 * ADR 0071 put it — the storefront.
 */
public interface CustomerReviewPort {

    /**
     * Records a 1–5 rating as {@code customerAccountId}.
     *
     * <p>Every eligibility rule is the one {@code ReviewSubmissionService}
     * already enforces — the order must exist, be this customer's, and have
     * reached {@code COMPLETED}, and it may carry only one review — restated
     * here as an outcome rather than an exception, because a bot answers a tap
     * with a sentence and has no error page.
     */
    Outcome rate(UUID tenantId, UUID brandId, UUID orderId, UUID customerAccountId, int rating);

    /**
     * Whether this order already carries a review.
     *
     * <p>Asked before a rating button is rendered, so a customer is not offered
     * a star row for something they already rated. Not a substitute for the
     * one-per-order constraint {@link #rate} runs into: this is what to show,
     * that is what is true.
     */
    boolean hasReview(UUID tenantId, UUID orderId);

    enum Outcome {
        RECORDED,
        /** ADR 0071's one-review-per-order constraint. */
        ALREADY_RATED,
        /** Not completed, not this customer's, or no such order — one answer, on purpose. */
        NOT_ELIGIBLE
    }
}
