package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * The order was cancelled (ADR 0019, ADR 0039).
 *
 * <p>ADR 0039 added the three outcome fields as an ADR 0032 additive change:
 * within one event version a schema may only grow, and all three are optional, so
 * a consumer written against v1 keeps working unchanged.
 *
 * <p>They are here because a reason code alone cannot answer the two questions
 * every report of a cancellation asks — what happened to the stock, and who
 * carried the cost. None of the three says anything about a person, which is what
 * lets them travel on an event while the reason's internal text may not: «Не
 * дозвонились» is a statement about a customer and stays behind an authorized
 * API.
 *
 * @param systemCategory   the platform-owned category, never the tenant's wording
 * @param stockDisposition what was actually done with the stock, which before the
 *                         hold was committed is always a release whatever the
 *                         reason said
 * @param liabilityParty   who carries the cost in the ADR 0043 reports
 */
public record OrderCancelled(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        String cancelledByActorType,
        String reasonCode,
        String previousStatus,
        String status,
        int orderVersion,
        @Nullable String systemCategory,
        @Nullable String stockDisposition,
        @Nullable String liabilityParty)
        implements OrderingEvent {

    public OrderCancelled {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    /**
     * The shape before ADR 0039, for a caller that has no outcome to carry.
     *
     * <p>Kept so the pre-0039 producers and the contract samples stay compilable
     * and keep meaning what they meant. A cancellation published through it
     * carries nulls in the three new fields, which is honest: the fields describe
     * a decision that path never made.
     */
    public OrderCancelled(
            UUID eventId,
            TenantId tenantId,
            UUID orderId,
            Instant occurredAt,
            UUID brandId,
            UUID locationId,
            String cancelledByActorType,
            String reasonCode,
            String previousStatus,
            String status,
            int orderVersion) {
        this(
                eventId,
                tenantId,
                orderId,
                occurredAt,
                brandId,
                locationId,
                cancelledByActorType,
                reasonCode,
                previousStatus,
                status,
                orderVersion,
                null,
                null,
                null);
    }

    @Override
    public String eventType() {
        return "OrderCancelled";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(
                orderId,
                brandId,
                locationId,
                cancelledByActorType,
                reasonCode,
                previousStatus,
                status,
                orderVersion,
                systemCategory,
                stockDisposition,
                liabilityParty);
    }

    public record Payload(
            UUID orderId,
            UUID brandId,
            UUID locationId,
            String cancelledByActorType,
            String reasonCode,
            String previousStatus,
            String status,
            int orderVersion,
            @Nullable String systemCategory,
            @Nullable String stockDisposition,
            @Nullable String liabilityParty) {}
}
