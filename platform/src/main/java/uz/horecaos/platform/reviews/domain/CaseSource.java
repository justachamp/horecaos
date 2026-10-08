package uz.horecaos.platform.reviews.domain;

/**
 * How a case came to exist. A case opens from a review only at launch (ADR 0159, open input 7): a phone
 * complaint with no review is not a case here.
 */
public enum CaseSource {
    /** Opened in the review's own transaction because the rating was at or under the policy threshold. */
    AUTO,
    /** Opened by hand from any review by a holder of {@code recovery.case.manage}. */
    MANUAL
}
