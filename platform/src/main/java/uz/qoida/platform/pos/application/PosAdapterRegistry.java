package uz.qoida.platform.pos.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import uz.qoida.platform.pos.api.PosCapability;
import uz.qoida.platform.pos.application.port.PosAdapter;

/**
 * The adapters this build has, by provider type (ADR 0011).
 *
 * <p>The only place a provider name is turned into behaviour. Everything above
 * this asks for a capability at a scope and gets an adapter or gets none, which
 * is what keeps the string "clopos" out of ordering, catalog, and every service
 * in this module.
 */
@Component
public class PosAdapterRegistry {

    private final Map<String, PosAdapter> byType;

    public PosAdapterRegistry(List<PosAdapter> adapters) {
        this.byType = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(PosAdapter::providerType, adapter -> adapter));
    }

    public Optional<PosAdapter> forProvider(String providerType) {
        return Optional.ofNullable(byType.get(providerType));
    }

    /**
     * Whether the vendor behind this provider type can do something at all.
     *
     * <p>The vendor ceiling, not this installation's answer. A credential whose
     * staff user lacks the permission still fails, and the capability snapshot is
     * where that is recorded — this only rules out asking a till for something its
     * API has never had.
     */
    public boolean declares(String providerType, PosCapability capability) {
        return forProvider(providerType)
                .map(adapter -> adapter.declaredCapabilities().contains(capability))
                .orElse(false);
    }
}
