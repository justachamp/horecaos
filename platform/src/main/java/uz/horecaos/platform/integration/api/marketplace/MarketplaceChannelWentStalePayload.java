package uz.horecaos.platform.integration.api.marketplace;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The version-1 payload of {@code MarketplaceChannelWentStale} on {@code integration.events}
 * (ADR 0040 "Liveness watermarks", ADR 0141 "When the partner is down", ADR 0032).
 *
 * <p>A marketplace binding has had at least one dish unconfirmed — the platform believes one
 * thing and has not been able to make the partner agree — for longer than its bound
 * ({@code marketplace.availability.stale_after_seconds}, ADR 0030). Published once per episode:
 * the binding is not reported again until it has had nothing unconfirmed past its bound and
 * then goes stale again.
 *
 * <p>Counts, identifiers and instants only. No dish names, no partner error text (ADR 0029).
 *
 * @param unconfirmedItemCount how many dishes were unconfirmed past the bound when it was raised
 * @param oldestUnconfirmedSince since when the longest-waiting of them has been unconfirmed
 * @param staleAfterSeconds the bound that was crossed
 */
public record MarketplaceChannelWentStalePayload(
        UUID bindingId,
        UUID locationId,
        String providerType,
        int staleAfterSeconds,
        int unconfirmedItemCount,
        Instant oldestUnconfirmedSince) {

    public MarketplaceChannelWentStalePayload {
        Objects.requireNonNull(bindingId, "A binding id is required");
        Objects.requireNonNull(locationId, "A location id is required");
        Objects.requireNonNull(providerType, "A provider type is required");
        Objects.requireNonNull(oldestUnconfirmedSince, "An oldest-unconfirmed time is required");
    }
}
