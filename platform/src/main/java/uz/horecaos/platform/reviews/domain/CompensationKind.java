package uz.horecaos.platform.reviews.domain;

/**
 * What a case's linked compensation is (ADR 0159, Decision 9). The case links and never pays: an
 * {@link #ORDER_REMEDY} was recorded through ADR 0048's console and a {@link #BENEFIT_GRANT} minted
 * through ADR 0044's, each under its own approval threshold.
 */
public enum CompensationKind {
    ORDER_REMEDY,
    BENEFIT_GRANT
}
