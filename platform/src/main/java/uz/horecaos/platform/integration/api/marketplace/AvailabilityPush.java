package uz.horecaos.platform.integration.api.marketplace;

import java.util.Objects;
import java.util.UUID;

/**
 * One state-set: make this item available or unavailable at the partner (ADR 0141
 * Decision 8: availability pushed to a marketplace is binary).
 *
 * <p>A <em>state</em>, not a delta: {@code available = true|false}, keyed {@code (binding,
 * item, sequence)}, so a retry is naturally idempotent and a partner recovering from an
 * outage is told the current truth, never an old event. Nothing here is personal data.
 *
 * @param externalItemId the partner's own id for the item, from the ADR 0040 {@code MENU_ITEM} mapping
 * @param sequence       {@code desired_seq}: advances only when the desired value changes
 */
public record AvailabilityPush(
        UUID tenantId,
        UUID bindingId,
        UUID installationId,
        String providerType,
        String externalItemId,
        boolean available,
        long sequence,
        String correlationId) {

    public AvailabilityPush {
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(bindingId, "A binding id is required");
        Objects.requireNonNull(installationId, "An installation id is required");
        Objects.requireNonNull(providerType, "A provider type is required");
        Objects.requireNonNull(externalItemId, "The partner's item id is required");
        Objects.requireNonNull(correlationId, "A correlation id is required");
    }
}
