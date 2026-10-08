package uz.horecaos.platform.content.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An item was taken out of front of customers (ADR 0157), by a withdrawal or by archiving it while it
 * was live. Every revision is kept.
 */
public record ContentItemWithdrawn(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        UUID itemId,
        String kind,
        @Nullable String placement,
        Reason reason,
        Instant occurredAt)
        implements ContentEvent {

    /** Why the item left the storefront. */
    public enum Reason {
        WITHDRAWN,
        ARCHIVED
    }

    public ContentItemWithdrawn {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(itemId, "Item ID is required");
        Objects.requireNonNull(kind, "A kind is required");
        Objects.requireNonNull(reason, "A reason is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "ContentItemWithdrawn";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public UUID aggregateId() {
        return itemId;
    }

    @Override
    public Object payload() {
        return new Payload(itemId, brandId, kind, placement, reason.name());
    }

    public record Payload(
            UUID itemId,
            UUID brandId,
            String kind,
            @Nullable String placement,
            String reason) {}
}
