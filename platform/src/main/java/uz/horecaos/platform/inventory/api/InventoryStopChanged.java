package uz.horecaos.platform.inventory.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A stop was made, lifted or expired (ADR 0141, ADR 0032 {@code
 * InventoryStopChanged} v1 on {@code inventory.events}).
 *
 * <p>Symmetric, like {@link ItemAvailabilityChanged}: {@code active} carries the
 * direction, so a dish going on stop and coming back are one event type. Carries
 * identifiers and stable codes only — no product name, no free text, no
 * personal data (ADR 0029). {@code brandId} is on the record for in-process
 * consumers (the marketplace dirty-marker listener needs to find a brand-wide
 * stop's bindings) and is not in the published payload, for the reason {@link
 * ItemAvailabilityChanged.Payload} gives: a consumer holding a {@code variantId}
 * can already resolve its brand.
 */
public record InventoryStopChanged(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        UUID stopId,
        UUID variantId,
        StopScopeType scopeType,
        @Nullable UUID locationId,
        @Nullable UUID menuId,
        @Nullable UUID channelId,
        StopSource source,
        boolean active,
        @Nullable Instant endsAt,
        String reasonCode,
        Instant occurredAt)
        implements InventoryEvent {

    public InventoryStopChanged {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(stopId, "Stop ID is required");
        Objects.requireNonNull(variantId, "Variant ID is required");
        Objects.requireNonNull(scopeType, "A scope type is required");
        Objects.requireNonNull(source, "A source is required");
        Objects.requireNonNull(reasonCode, "A reason code is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "InventoryStopChanged";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(
                stopId,
                variantId,
                scopeType.name(),
                locationId,
                menuId,
                channelId,
                source.name(),
                active,
                endsAt,
                reasonCode);
    }

    /**
     * The ADR 0141 version-1 payload. {@code scopeType} and {@code source} are
     * carried as their stable names; {@code endsAt} serialises as an ISO-8601
     * instant.
     */
    public record Payload(
            UUID stopId,
            UUID variantId,
            String scopeType,
            @Nullable UUID locationId,
            @Nullable UUID menuId,
            @Nullable UUID channelId,
            String source,
            boolean active,
            @Nullable Instant endsAt,
            String reasonCode) {}
}
