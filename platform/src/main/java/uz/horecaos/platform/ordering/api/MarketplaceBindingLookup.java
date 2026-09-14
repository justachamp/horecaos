package uz.horecaos.platform.ordering.api;

import java.util.Optional;
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
}
