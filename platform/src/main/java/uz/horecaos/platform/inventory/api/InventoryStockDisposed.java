package uz.horecaos.platform.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stock an order no longer needs was given back or written off (ADR 0169, ADR 0032 {@code InventoryStockDisposed}
 * v1 on {@code inventory.events}).
 *
 * <p>The fact the write-off report ADR 0039 promised is built from. Carries identifiers, stable codes and
 * counts only: no customer, address, note or product name (ADR 0029). Partitioned by the order, not by a
 * variant, because one disposal covers several variants and its consumers care which order it belongs to;
 * {@link #variantId()} is the first line's, only to satisfy the interface every inventory fact shares.
 */
public record InventoryStockDisposed(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID orderId,
        String disposition,
        String reasonCategory,
        List<Line> lines,
        Instant occurredAt)
        implements InventoryEvent {

    public InventoryStockDisposed {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(disposition, "A disposition is required");
        Objects.requireNonNull(reasonCategory, "A reason category is required");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A disposal fact names at least one line");
        }
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "InventoryStockDisposed";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public UUID variantId() {
        return lines.getFirst().variantId();
    }

    @Override
    public String aggregateType() {
        return "Order";
    }

    @Override
    public UUID aggregateId() {
        return orderId;
    }

    @Override
    public Object payload() {
        return new Payload(orderId, brandId, locationId, disposition, reasonCategory, lines);
    }

    public record Line(
            UUID variantId, int units, int releasedUnits, int returnedUnits, int writtenOffUnits, int noEffectUnits) {}

    /** The ADR 0169 version-1 payload. */
    public record Payload(
            UUID orderId, UUID brandId, UUID locationId, String disposition, String reasonCategory, List<Line> lines) {}
}
