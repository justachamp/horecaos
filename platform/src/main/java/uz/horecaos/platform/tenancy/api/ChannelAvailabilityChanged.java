package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One of a channel's whole-matrix writes settled (ADR 0036).
 *
 * <p>Fired by {@code SalesChannelService.replacePaymentMethods},
 * {@code replaceFulfillmentModes} and {@code replaceLocations} alike — the three
 * writes that decide what a channel gates ("payment methods, fulfilment modes,
 * catalog visibility, and the price plane... and nothing else keys on it").
 * {@code matrixKind} says which of the first two matrices moved, or that the
 * channel's location bindings did; it never carries the matrix itself, per ADR
 * 0032's rule that an event carries identifiers and never contents. A consumer
 * that needs the new shape re-reads it through {@link SalesChannelLookup}.
 */
public record ChannelAvailabilityChanged(
        UUID eventId, TenantId tenantId, UUID channelId, Instant occurredAt, String matrixKind, int version)
        implements TenancyEvent {

    /** The matrix this write replaced. Never the price plane: that is its own column, not a matrix. */
    public enum MatrixKind {
        PAYMENT_METHODS,
        FULFILLMENT_MODES,
        LOCATIONS
    }

    public ChannelAvailabilityChanged {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(channelId, "Channel ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(matrixKind, "Matrix kind is required");
    }

    @Override
    public String eventType() {
        return "ChannelAvailabilityChanged";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String aggregateType() {
        return "SalesChannel";
    }

    @Override
    public UUID aggregateId() {
        return channelId;
    }

    @Override
    public Object payload() {
        return new Payload(channelId, matrixKind, version);
    }

    public record Payload(UUID channelId, String matrixKind, int version) {}
}
