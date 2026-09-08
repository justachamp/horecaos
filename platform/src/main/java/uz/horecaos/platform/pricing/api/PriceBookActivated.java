package uz.horecaos.platform.pricing.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A price book was put in front of customers (ADR 0018).
 *
 * <p>Fired once per activation, after {@code
 * uz.horecaos.platform.pricing.application.PriceAuthoringService#activate} has
 * already won the conditional update — so this event, the ADR 0027 audit fact
 * it is written beside, and the version bump the context hash pins all describe
 * the one activation that actually happened, never a race that lost.
 *
 * <p>Carries {@code version} — the book's new version after activation, the
 * same number a stale quote's context hash fails to match — and nothing about
 * what the book prices: a consumer that needs the amounts calls the authorized
 * {@code PriceQueryService} read with {@code priceBookId}, the same discipline
 * {@code ItemAvailabilityChanged} applies to a product name.
 *
 * <p>Also a {@link PricingEvent} — the ADR 0032 {@code PriceBookActivated}
 * contract: {@code
 * uz.horecaos.platform.integration.outbox.PricingOutboxEventListener} appends
 * it to {@code pricing.events} in the same {@code BEFORE_COMMIT} transaction as
 * the activation and its audit fact.
 */
public record PriceBookActivated(
        UUID eventId, UUID tenantId, UUID brandId, UUID priceBookId, int version, String currency, Instant occurredAt)
        implements PricingEvent {

    public PriceBookActivated {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(priceBookId, "Price book ID is required");
        Objects.requireNonNull(currency, "Currency is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "PriceBookActivated";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(priceBookId, brandId, version, currency);
    }

    /** Identifiers and the version a stale quote's hash fails against — never an amount. */
    public record Payload(UUID priceBookId, UUID brandId, int version, String currency) {}
}
