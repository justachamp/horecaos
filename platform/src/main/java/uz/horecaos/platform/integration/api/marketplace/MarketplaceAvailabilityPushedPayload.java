package uz.horecaos.platform.integration.api.marketplace;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The version-1 payload of {@code MarketplaceAvailabilityPushed} on {@code integration.events}
 * (ADR 0040, ADR 0141 Decision 7, ADR 0032).
 *
 * <p>One confirmed change of what a partner holds for one mapped dish: the reconciler told the
 * partner {@code available} and the partner answered with a success. Only a success is
 * published, once, in the transaction that records it: a push the partner refused or never
 * answered says nothing (the {@code UNKNOWN} outcome withdraws the platform's belief; it is not a
 * confirmation), and a worker that lost its lease while the call was in flight records and
 * publishes nothing.
 *
 * <p>Identifiers, a boolean and a sequence: the partner's own item id is the partner's
 * identifier for a dish, not a name, and nothing here is personal data (ADR 0029). A consumer
 * resolves the dish's name through the authorized catalog API with {@code variantId}.
 *
 * @param bindingId the ADR 0026 {@code MARKETPLACE} binding the partner was told through
 * @param desiredSeq the state-set's sequence ({@code (binding, item, desired_seq)} keys the call)
 * @param confirmedAt when the partner's success answer was recorded
 */
public record MarketplaceAvailabilityPushedPayload(
        UUID bindingId,
        UUID locationId,
        UUID variantId,
        String providerType,
        String externalItemId,
        boolean available,
        long desiredSeq,
        Instant confirmedAt) {

    public MarketplaceAvailabilityPushedPayload {
        Objects.requireNonNull(bindingId, "A binding id is required");
        Objects.requireNonNull(locationId, "A location id is required");
        Objects.requireNonNull(variantId, "A variant id is required");
        Objects.requireNonNull(providerType, "A provider type is required");
        Objects.requireNonNull(externalItemId, "An external item id is required");
        Objects.requireNonNull(confirmedAt, "A confirmation time is required");
    }
}
