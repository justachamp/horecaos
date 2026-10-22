package uz.horecaos.platform.catalog.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A brand's draft menu changed (ADR 0016 "Events", ADR 0137, ADR 0138, ADR 0032 {@code CatalogDraftChanged} v1
 * on {@code catalog.events}).
 *
 * <p>Says <em>that</em> something in the draft moved, and which entity, never what it became: a product created,
 * a variant's photos replaced on a channel, a classification filled, a category archived. A consumer such as a
 * marketplace push, which must re-project the channel's menu, re-reads the draft through the projection; the
 * fact is only what tells it to. It is the draft that changed, not a publication: nothing a customer is served
 * moves until the draft is published ({@code CatalogPublished}).
 *
 * <p>Written to the outbox in the same {@code BEFORE_COMMIT} transaction as the write and its audit fact.
 * Several writes to one entity in one transaction are one fact (see {@code CatalogDraftSignal}).
 *
 * @param entityType the kind of entity that changed ({@code PRODUCT}, {@code VARIANT}, {@code CATEGORY}, ...), a
 *     stable code and not a class name
 * @param entityId the entity, or null for a batch that spans many (a bulk classification)
 * @param change what happened to it
 * @param channelId the sales channel the change is scoped to, or null when it is the draft's own; a channel
 *     override or a channel offering names the channel it changes the projection of
 */
public record CatalogDraftChanged(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        String entityType,
        @Nullable UUID entityId,
        Change change,
        @Nullable UUID channelId,
        Instant occurredAt)
        implements CatalogEvent {

    /** The stable codes a consumer may branch on. Additive: a new code is a new value, never a new meaning. */
    public enum Change {
        CREATED,
        UPDATED,
        ARCHIVED,
        TRANSLATED,
        MEDIA_CHANGED,
        CLASSIFIED,
        PHYSICAL_ATTRIBUTES_CHANGED,
        COMPOSITE_CHANGED,
        CHANNEL_MEDIA_REPLACED,
        CHANNEL_OFFERING_CHANGED
    }

    public CatalogDraftChanged {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(entityType, "An entity type is required");
        Objects.requireNonNull(change, "A change code is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "CatalogDraftChanged";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public UUID aggregateId() {
        return brandId;
    }

    @Override
    public Object payload() {
        return new Payload(brandId, entityType, entityId, change.name(), channelId);
    }

    public record Payload(
            UUID brandId,
            String entityType,
            @Nullable UUID entityId,
            String change,
            @Nullable UUID channelId) {}
}
