package uz.horecaos.platform.pricing.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A promotion was put in front of customers (ADR 0140, ADR 0018).
 *
 * <p>Fired once per activation, after the conditional update has won, so this
 * event, the ADR 0027 audit fact it is written beside and the definition version
 * recorded in the history all describe the one activation that happened. Carries
 * ids, the definition version, scope, kind and window and nothing else: a consumer
 * that needs the rule reads it through the authorized promotions API, the way a
 * {@link PriceBookActivated} consumer reads prices. No consumer is specified here.
 *
 * <p>Also a {@link PricingEvent}: {@code PricingOutboxEventListener} appends it to
 * {@code pricing.events} in the same {@code BEFORE_COMMIT} transaction as the
 * activation.
 */
public record PromotionActivated(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        UUID promotionId,
        int definitionVersion,
        String scope,
        String kind,
        Instant validFrom,
        @Nullable Instant validUntil,
        Instant occurredAt)
        implements PricingEvent {

    public PromotionActivated {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(promotionId, "Promotion ID is required");
        Objects.requireNonNull(scope, "Scope is required");
        Objects.requireNonNull(kind, "Kind is required");
        Objects.requireNonNull(validFrom, "Valid-from is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "PromotionActivated";
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
        return new Payload(promotionId, brandId, definitionVersion, scope, kind, validFrom, validUntil);
    }

    /** Identifiers, the version, scope, kind and window -- never a condition, an operand or an amount. */
    public record Payload(
            UUID promotionId,
            UUID brandId,
            int definitionVersion,
            String scope,
            String kind,
            Instant validFrom,
            @Nullable Instant validUntil) {}
}
