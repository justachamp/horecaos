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
 * <p>ADR 0018 names six pricing facts (this one, {@code PromotionActivated},
 * {@code PromotionSuspended}, {@code PricingQuoteCreated}, {@code
 * PricingQuoteAccepted}, and the coupon/benefit lifecycle events). Only {@link
 * PriceBookActivated} is published today, because it is the only one with a
 * producer: there is no promotion-activation flow, no benefit grant, and
 * quote creation/acceptance are high-volume per-request facts whose payload
 * shape and retention deserve their own decision rather than riding along with
 * a once-a-day control-plane activation. Inventing a payload for an event with
 * no producer would be exactly the unreviewed contract ADR 0032 exists to
 * prevent — see this catalogue's own restraint on {@code MediaAssetAvailable}'s
 * unpublished siblings for the same reasoning applied first.
 */
public sealed interface PricingEvent permits PriceBookActivated {

    UUID eventId();

    String eventType();

    int eventVersion();

    UUID tenantId();

    UUID priceBookId();

    Instant occurredAt();

    Object payload();

    default String aggregateType() {
        return "PriceBook";
    }

    default UUID aggregateId() {
        return priceBookId();
    }
}
