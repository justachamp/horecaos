package uz.horecaos.platform.integration.provider;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.ordering.api.MarketplaceBindingLookup;

/**
 * {@link MarketplaceBindingLookup} over {@link ProviderInstallationLookup}.
 *
 * <p>This class is the whole reason {@code ordering} compiles without a
 * dependency on {@code integration} (ADR 0040, wave P14's row {@code 1.3g}):
 * ordering names the question in {@link MarketplaceBindingLookup}'s terms,
 * and this adapter — which may depend on both modules, since it lives in
 * neither's business logic — answers it from the real installation registry.
 */
@Component
class OrderingMarketplaceBindingAdapter implements MarketplaceBindingLookup {

    private final ProviderInstallationLookup installations;

    OrderingMarketplaceBindingAdapter(ProviderInstallationLookup installations) {
        this.installations = installations;
    }

    @Override
    public Optional<UUID> bindingForInstallation(
            UUID tenantId, UUID installationId, UUID brandId, @Nullable UUID locationId) {
        return installations
                .bindingForInstallation(tenantId, installationId, brandId, locationId)
                .map(BindingRef::bindingId);
    }
}
