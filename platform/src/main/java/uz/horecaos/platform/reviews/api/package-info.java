/**
 * What reviews exposes to other modules: submitting one rating on behalf of the customer who owns the
 * order and asking whether an order already carries one (ADR 0071, consumed by ADR 0075); the facts it
 * publishes (ADR 0159: {@code ReviewsEvent}); the prompt-timing policy {@code notifications} asks and the
 * alert settings it reads; the two fact sources {@code reporting} builds its day-close facts from; and the
 * configuration keys. Nothing of the moderation model, the tenant-facing read side, or the
 * envelope-encrypted comment and case notes.
 */
@org.springframework.modulith.NamedInterface("api")
package uz.horecaos.platform.reviews.api;
