package uz.horecaos.platform.ordering.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The one fact a manually-keyed aggregator order needs from ADR 0026: which
 * provider binding a channel's own installation resolves to at this branch
 * (ADR 0040, gap map row {@code 1.3g}).
 *
 * <p>Ordering never sees a {@code ProviderInstallationLookup} or a {@code
 * BindingRef} — that would give {@code integration} module a dependency edge
 * back onto {@code ordering} (via {@code OrderDirectory}, which several
 * integration adapters already read) and {@code ordering} a dependency edge
 * onto {@code integration} in the same build, which {@code
 * ModularArchitectureTests} rejects as a module cycle. This interface is the
 * seam: ordering names the question in its own terms (an installation id, a
 * branch, and the bare {@code bindingId} it needs to write), and the
 * integration module answers it.
 */
public interface MarketplaceBindingLookup {

    /**
     * The single active binding of a known installation that covers this
     * branch, chosen by location specificity.
     *
     * @return empty when the installation has no active binding covering this
     *         scope
     */
    Optional<UUID> bindingForInstallation(UUID tenantId, UUID installationId, UUID brandId, @Nullable UUID locationId);

    /**
     * The names an operator knows these bindings by (gap map row {@code 1.1c},
     * the board's «Агрегатор» filter): the provider and the installation's own
     * display name, for bindings that orders already point at. Ordering holds
     * only the bare id ({@code ordering.orders.marketplace_binding_id}); what it
     * is called is integration's to say.
     *
     * <p>A binding that no longer resolves (or a build with no integration
     * adapter) is simply absent from the answer, and the caller shows its id
     * rather than inventing a name. Defaulted to empty so a test double of this
     * port that predates the board's binding filter need not grow a mechanical
     * implementation.
     */
    default Map<UUID, BindingLabel> labelsOf(UUID tenantId, Set<UUID> bindingIds) {
        return Map.of();
    }

    /**
     * A binding's operator-facing name. Neither field is a credential or personal
     * data: the provider code is a catalogue value and the display name is what
     * the tenant typed when it registered the installation.
     */
    record BindingLabel(String providerType, String displayName) {}
}
