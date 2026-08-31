package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * An order exists and is durable (ADR 0019, step 8 of the checkout transaction).
 *
 * <p>Emitted into the outbox inside the checkout transaction, so the order and
 * the fact that it exists commit together. Nothing external has happened yet:
 * payment, approval, export, and notification are all consequences of this fact
 * rather than preconditions of it.
 *
 * <p>{@code lineCount} rather than the lines themselves. A consumer that needs
 * to know what was ordered calls the order API; putting the basket on a topic
 * would put customer notes and, eventually, addresses there too.
 */
public record OrderReceived(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        String channelCode,
        String publicOrderNumber,
        String fulfillmentMode,
        String acceptanceMode,
        @Nullable UUID acceptancePolicyId,
        int acceptancePolicyVersion,
        String status,
        int orderVersion,
        String currency,
        long totalMinor,
        int lineCount)
        implements OrderingEvent {

    public OrderReceived {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(status, "Order status is required");
    }

    @Override
    public String eventType() {
        return "OrderReceived";
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
                channelCode,
                publicOrderNumber,
                fulfillmentMode,
                acceptanceMode,
                acceptancePolicyId,
                acceptancePolicyVersion,
                status,
                orderVersion,
                currency,
                totalMinor,
                lineCount);
    }

    public record Payload(
            UUID orderId,
            UUID brandId,
            UUID locationId,
            String channelCode,
            String publicOrderNumber,
            String fulfillmentMode,
            String acceptanceMode,
            @Nullable UUID acceptancePolicyId,
            int acceptancePolicyVersion,
            String status,
            int orderVersion,
            String currency,
            long totalMinor,
            int lineCount) {}
}
