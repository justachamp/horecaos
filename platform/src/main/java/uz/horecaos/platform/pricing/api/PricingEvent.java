package uz.horecaos.platform.pricing.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A versioned business fact emitted by the pricing module (ADR 0018, ADR 0032).
 *
 * <p>Sealed, in the {@code InventoryEvent} / {@code MediaEvent} / {@code
 * VoiceEvent} genre, so a future subtype cannot reach {@link
 * uz.horecaos.platform.integration.outbox.PricingOutboxEventListener} without a
 * catalogue entry, a schema, and a documentation row — the listener calls
 * {@code EventCatalog.require} before it appends anything, and this interface is
 * what a completeness test would enumerate to prove that requirement actually
 * reaches every publishable pricing event.
 *
 * <p>ADR 0018 names six pricing facts. Three are published today, each because it
 * has a producer: {@link PriceBookActivated}, and (ADR 0140, with the
 * promotion lifecycle that produces them) {@link PromotionActivated} and {@link
 * PromotionSuspended}. The rest -- {@code PricingQuoteCreated}, {@code
 * PricingQuoteAccepted} and the coupon/benefit lifecycle events -- are still
 * unpublished: quote creation and acceptance are high-volume per-request facts
 * whose payload shape and retention deserve their own decision, and inventing a
 * payload for an event with no producer would be exactly the unreviewed contract
 * ADR 0032 exists to prevent.
 */
public sealed interface PricingEvent permits PriceBookActivated, PromotionActivated, PromotionSuspended {

    UUID eventId();

    String eventType();

    int eventVersion();

    UUID tenantId();

    Instant occurredAt();

    Object payload();

    /** The kind of aggregate the fact is about; the outbox partitions by its id. */
    String aggregateType();

    UUID aggregateId();
}
