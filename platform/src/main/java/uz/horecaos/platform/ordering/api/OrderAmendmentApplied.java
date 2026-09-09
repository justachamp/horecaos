package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * An amendment committed, appending a new order revision (ADR 0039).
 *
 * <p>Fires once, from {@code OrderAmendmentService.apply}, in the same
 * transaction as the revision insert and the order's field patch — never on a
 * duplicate apply, which returns the settled amendment without doing the work
 * again. {@code appliedRevision} is the revision this amendment produced;
 * {@link OrderRevisionCreated} carries the same revision number as its own fact
 * about the order's revision chain, and the two are published together rather
 * than one implying the other, because a consumer of only the revision chain
 * should not have to know amendments exist to follow it.
 *
 * <p>{@code deltaTotalMinor} is zero for every amendment this release can apply
 * — the three built commands change no money — and is carried anyway because
 * the field means the same thing once a financial command ships.
 */
public record OrderAmendmentApplied(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        UUID amendmentId,
        int appliedRevision,
        List<String> commandTypes,
        long deltaTotalMinor,
        int orderVersion)
        implements OrderingEvent {

    public OrderAmendmentApplied {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(amendmentId, "Amendment ID is required");
        commandTypes = List.copyOf(commandTypes);
    }

    @Override
    public String eventType() {
        return "OrderAmendmentApplied";
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
                amendmentId,
                appliedRevision,
                commandTypes,
                deltaTotalMinor,
                orderVersion);
    }

    public record Payload(
            UUID orderId,
            UUID brandId,
            UUID locationId,
            UUID amendmentId,
            int appliedRevision,
            List<String> commandTypes,
            long deltaTotalMinor,
            int orderVersion) {}
}
