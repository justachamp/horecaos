package uz.horecaos.platform.marketing.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An offer version was put in front of campaigns (ADR 0112). Carries the id, the brand
 * and the version number, never the reference or the wording: a consumer reads the
 * offer through the authorized offers API, the way a {@code PromotionActivated}
 * consumer reads the rule.
 */
public record OfferPublished(UUID eventId, UUID tenantId, UUID brandId, UUID offerId, int version, Instant occurredAt)
        implements MarketingEvent {

    public OfferPublished {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(offerId, "Offer ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "OfferPublished";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String aggregateType() {
        return "MarketingOffer";
    }

    @Override
    public UUID aggregateId() {
        return offerId;
    }

    @Override
    public Object payload() {
        return new Payload(offerId, brandId, version);
    }

    public record Payload(UUID offerId, UUID brandId, int version) {}
}
