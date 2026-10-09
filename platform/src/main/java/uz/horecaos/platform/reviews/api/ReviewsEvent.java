package uz.horecaos.platform.reviews.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A versioned business fact emitted by the reviews module (ADR 0159, Decision 13, ADR 0032).
 *
 * <p>Sealed, in the {@code CustomersEvent} / {@code PricingEvent} genre, so a future subtype cannot
 * reach {@code ReviewsOutboxEventListener} without a catalogue entry, a schema and a documentation row.
 * Published on {@code reviews.events}, keyed by the order, so one order's review, moderation and case
 * facts stay in order on their topic.
 *
 * <p>Every payload is identifiers, states, stable codes and times -- never a comment, a case note, a
 * tag label, a customer or a staff member's name (ADR 0029). A staff member appears only as the
 * non-personal {@code display_reference} (ADR 0139), such as {@code S-0142}.
 *
 * <p>These are also in-process Spring events: {@code notifications} listens to the case events to raise
 * an operations alert, which is why the module exposes them rather than the outbox rows.
 */
public sealed interface ReviewsEvent
        permits ReviewSubmitted,
                ReviewCommentModerated,
                RecoveryCaseOpened,
                RecoveryCaseStateChanged,
                RecoveryCaseSlaBreached {

    UUID eventId();

    String eventType();

    int eventVersion();

    UUID tenantId();

    UUID orderId();

    Instant occurredAt();

    Object payload();

    default String aggregateType() {
        return "OrderReview";
    }

    default UUID aggregateId() {
        return orderId();
    }
}
