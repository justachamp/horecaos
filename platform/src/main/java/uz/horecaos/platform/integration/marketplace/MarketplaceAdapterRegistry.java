package uz.horecaos.platform.integration.marketplace;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceAvailabilityAdapter;

/**
 * The marketplace availability adapters wired in this build, by provider type (ADR 0141).
 *
 * <p>A provider type with no entry here is not an error: it is an aggregator that has no stop
 * API a third party can call (or one this build has not integrated), and the platform says so
 * where the operator will see it — {@code MANUAL}, "not propagated automatically" — rather than
 * pretending a push happened. ADR 0011's rule: an unsupported capability may never be the sole
 * business path.
 */
@Component
public class MarketplaceAdapterRegistry {

    /** The capability an adapter that is registered here declares (ADR 0040). */
    public static final String AVAILABILITY_PUSH = "marketplace.availability.push";

    private final Map<String, MarketplaceAvailabilityAdapter> byProvider;

    public MarketplaceAdapterRegistry(List<MarketplaceAvailabilityAdapter> adapters) {
        this.byProvider = adapters.stream()
                .collect(
                        Collectors.toUnmodifiableMap(MarketplaceAvailabilityAdapter::providerType, adapter -> adapter));
    }

    public Optional<MarketplaceAvailabilityAdapter> forProvider(String providerType) {
        return Optional.ofNullable(byProvider.get(providerType));
    }

    public boolean propagates(String providerType) {
        return byProvider.containsKey(providerType);
    }
}
