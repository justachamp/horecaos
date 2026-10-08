package uz.horecaos.platform.content.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An item was put in front of customers, or its next revision was (ADR 0157).
 *
 * <p>Not "an item became visible": a published item may be scheduled for next week, which is why the
 * window travels with the fact. Written to the outbox in the same {@code BEFORE_COMMIT} transaction
 * as the change and its audit fact.
 */
public record ContentItemPublished(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        UUID itemId,
        String kind,
        @Nullable String placement,
        int revisionNo,
        @Nullable Instant startsAt,
        @Nullable Instant endsAt,
        List<String> channelCodes,
        Instant occurredAt)
        implements ContentEvent {

    public ContentItemPublished {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(itemId, "Item ID is required");
        Objects.requireNonNull(kind, "A kind is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        channelCodes = List.copyOf(channelCodes);
    }

    @Override
    public String eventType() {
        return "ContentItemPublished";
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
        return new Payload(itemId, brandId, kind, placement, revisionNo, startsAt, endsAt, channelCodes);
    }

    public record Payload(
            UUID itemId,
            UUID brandId,
            String kind,
            @Nullable String placement,
            int revisionNo,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            List<String> channelCodes) {}
}
