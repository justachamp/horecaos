package uz.horecaos.platform.pricing.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A promotion was taken out of front of customers (ADR 0140, ADR 0018).
 *
 * <p>Suspending stops new orders from getting the promotion; an order that already
 * holds it keeps it under its recorded definition version. The payload is ids, the
 * definition version, scope and kind -- the same discipline as {@link
 * PromotionActivated}.
 */
public record PromotionSuspended(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        UUID promotionId,
        int definitionVersion,
        String scope,
        String kind,
        Instant occurredAt)
        implements PricingEvent {

    public PromotionSuspended {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(promotionId, "Promotion ID is required");
        Objects.requireNonNull(scope, "Scope is required");
        Objects.requireNonNull(kind, "Kind is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "PromotionSuspended";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String aggregateType() {
        return "Promotion";
    }

    @Override
    public UUID aggregateId() {
        return promotionId;
    }

    @Override
    public Object payload() {
        return new Payload(promotionId, brandId, definitionVersion, scope, kind);
    }

    public record Payload(UUID promotionId, UUID brandId, int definitionVersion, String scope, String kind) {}
}
