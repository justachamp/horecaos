package uz.horecaos.platform.catalog.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a branch's channels may sell has just changed: an offering switched, a channel exclusion
 * added or removed, a menu bound or unbound (ADR 0016, ADR 0036, row 4.4a; ADR 0141).
 *
 * <p>An in-process fact, not an outbox event: it names no dish and carries nothing a consumer
 * could act on except "recompute". The marketplace reconciler listens to it to ask for an early
 * sweep (an accelerator — the resync sweep converges every one of these changes within one
 * interval whether or not this was heard), so the change reaches a connected aggregator in
 * seconds instead of minutes.
 *
 * @param locationId null when the change is not tied to one branch — a brand-wide channel
 *     exclusion — so every branch of the brand may be affected
 */
public record ChannelAssortmentChanged(
        UUID tenantId, UUID brandId, @Nullable UUID locationId, Instant occurredAt) {

    public ChannelAssortmentChanged {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }
}
