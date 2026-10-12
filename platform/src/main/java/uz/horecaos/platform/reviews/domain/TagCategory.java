package uz.horecaos.platform.reviews.domain;

/**
 * Which part of the visit a feedback tag is about (ADR 0159, Decision 1).
 *
 * <p>This is how the IA's four dimensions -- meal, operator, courier, delivery time -- are answered
 * without four scores: a customer taps what went wrong, and the per-dimension view is a count of tags
 * by category and sentiment per location and day. The set is owned by code, never by a tenant: adding
 * a category is a release, because a report groups by it.
 *
 * <p>{@link #COURIER} is a category for counting and for nothing else. ADR 0042 makes couriers
 * self-employed and {@code intent/0004-rating-what-happened} warns that a score affecting how much
 * work a courier is offered is a long way down the road to reclassification, so no dispatch, pay or
 * availability decision reads it and no courier-facing view exists (ADR 0159, open input 8).
 */
public enum TagCategory {
    FOOD,
    SERVICE,
    COURIER,
    DELIVERY_TIME,
    OTHER
}
