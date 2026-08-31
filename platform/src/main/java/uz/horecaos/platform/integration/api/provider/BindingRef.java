package uz.horecaos.platform.integration.api.provider;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the ADRs call {@code provider_binding_id} (ADR 0026).
 *
 * <p>ADR 0013 attaches merchant accounts to it, ADR 0014 attaches quotes and
 * assignment attempts, ADR 0020 attaches notification attempts. All three
 * referenced it before it existed; this is the definition.
 *
 * <p>Domain modules receive one of these plus a capability. They never see a
 * credential, a base URL, or a provider DTO.
 *
 * @param brandId    always present: the compact constructor below throws
 *                   before a binding without one is ever built
 * @param locationId null for a brand-wide binding — see {@link #scopeKey()}
 */
public record BindingRef(
        UUID bindingId,
        UUID installationId,
        UUID tenantId,
        ProviderCategory category,
        String providerType,
        UUID brandId,
        @Nullable UUID locationId) {

    public BindingRef {
        Objects.requireNonNull(bindingId, "A binding id is required");
        Objects.requireNonNull(installationId, "An installation id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(category, "A provider category is required");
        Objects.requireNonNull(providerType, "A provider type is required");
        if (locationId != null && brandId == null) {
            throw new IllegalArgumentException("A location binding must also identify its brand");
        }
        if (brandId == null && locationId == null) {
            throw new IllegalArgumentException("A binding must name a brand or a location");
        }
    }

    /** The narrowest identifier this binding applies at. */
    public UUID scopeKey() {
        return locationId != null ? locationId : brandId;
    }
}
