package uz.horecaos.platform.integration.marketplace;

import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceAvailabilityAdapter;
import uz.horecaos.platform.integration.api.provider.ProviderCapabilityCatalog;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;

/**
 * ADR 0026 declaration backed by the marketplace adapters wired in this build: an adapter that
 * is registered declares {@code marketplace.availability.push}, and a provider type with none
 * declares nothing — which is what makes its bindings show {@code MANUAL}.
 */
@Component
public class MarketplaceProviderCapabilityCatalog implements ProviderCapabilityCatalog {

    private final MarketplaceAdapterRegistry adapters;

    public MarketplaceProviderCapabilityCatalog(MarketplaceAdapterRegistry adapters) {
        this.adapters = adapters;
    }

    @Override
    public ProviderCategory category() {
        return ProviderCategory.MARKETPLACE;
    }

    @Override
    public Optional<Declaration> declarationFor(String providerType) {
        return adapters.forProvider(providerType)
                .map(MarketplaceAvailabilityAdapter::adapterVersion)
                .map(version -> new Declaration(Set.of(MarketplaceAdapterRegistry.AVAILABILITY_PUSH), version));
    }
}
