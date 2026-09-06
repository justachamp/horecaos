/**
 * What reviews exposes to other modules: submitting one rating on behalf of the
 * customer who owns the order, and asking whether an order already carries one
 * (ADR 0071, consumed by ADR 0075). Nothing of the moderation model, the
 * tenant-facing read side, or the envelope-encrypted comment.
 */
@org.springframework.modulith.NamedInterface("api")
package uz.horecaos.platform.reviews.api;
