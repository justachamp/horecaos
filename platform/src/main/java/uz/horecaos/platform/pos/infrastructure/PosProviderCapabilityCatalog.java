package uz.horecaos.platform.pos.infrastructure;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.provider.ProviderCapabilityCatalog;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.pos.application.port.PosAdapter;

/**
 * ADR 0026 declaration backed by the POS adapters wired in this build —
 * gap-map row 10.8a's fix path: the "Bind to a branch" dialog's
 * capability-assignment picker reads this (through {@code
 * ProviderCapabilityReconciliationService#declaredCapabilities}) so a POS
 * installation can be bound to a branch with a real, non-empty {@code
 * binding_capabilities} set, the same shape {@link
 * uz.horecaos.platform.integration.provider.DeliveryProviderCapabilityCatalog}
 * already gives DELIVERY.
 *
 * <p>Distinct from {@code PosCapabilityService}'s per-installation live
 * discovery (ADR 0011 steps three to seven): that establishes what one
 * restaurant's own credential can actually do, evidenced against the
 * provider, and is what caps a binding's capabilities as {@code SUPPORTED}
 * before activation. This class only answers the vendor ceiling — {@link
 * PosAdapter#declaredCapabilities()} — before any credential is considered,
 * which is exactly what a picker needs to default to "every capability the
 * installation's provider declares" before that installation has ever been
 * reconciled. It is never consulted by {@code
 * ProviderCapabilityReconciliationService#reconcile}, which refuses POS
 * outright (see that method's own guard) and defers entirely to the
 * live-discovery path instead.
 */
@Component
public class PosProviderCapabilityCatalog implements ProviderCapabilityCatalog {

    private final Map<String, PosAdapter> byProviderType;

    public PosProviderCapabilityCatalog(List<PosAdapter> adapters) {
        this.byProviderType =
                adapters.stream().collect(Collectors.toUnmodifiableMap(PosAdapter::providerType, adapter -> adapter));
    }

    @Override
    public ProviderCategory category() {
        return ProviderCategory.POS;
    }

    @Override
    public Optional<Declaration> declarationFor(String providerType) {
        return Optional.ofNullable(byProviderType.get(providerType))
                .map(adapter -> new Declaration(
                        adapter.declaredCapabilities().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()),
                        "pos/%s/v1".formatted(providerType)));
    }
}
