package uz.horecaos.platform.integration.api.provider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Resolves which external account handles a capability at a scope (ADR 0026).
 *
 * <p>Resolution runs up the ancestry: a location binding wins over its brand's,
 * matching the ADR 0030 precedence rule rather than introducing a second one.
 */
public interface ProviderInstallationLookup {

    /**
     * The single provider that should handle this capability here.
     *
     * @param locationId null to resolve at brand scope only, for a capability
     *                   such as SMS verification that a tenant binds once per
     *                   brand rather than per location
     */
    Optional<BindingRef> primaryBinding(
            UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode);

    /**
     * Every eligible provider, for capabilities where asking several is safe.
     * ADR 0014 quotes multiple couriers this way before booking exactly one.
     */
    List<BindingRef> candidateBindings(
            UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode);

    /** Non-sensitive installation detail. Never a credential. */
    Optional<InstallationSnapshot> installation(UUID tenantId, UUID installationId);

    /**
     * A binding by its own id, with no capability or scope reasoning applied.
     *
     * <p>Every existing caller asks "which binding handles this capability at
     * this scope" and gets back the primary one. ADR 0058's Telegram fan-out asks
     * a different question: a notification already names the exact chat it is
     * for (recorded on its endpoint at creation), so resolving it is a lookup by
     * id, not a selection among candidates. Defaulted to empty so the several
     * test doubles of this port that predate that need not all grow a mechanical
     * implementation.
     */
    default Optional<BindingRef> binding(UUID tenantId, UUID bindingId) {
        return Optional.empty();
    }

    /**
     * A binding's non-sensitive installation detail, resolved from ADR 0026.
     *
     * @param secretReference an ADR 0028 reference, resolved at call time by the
     *                        adapter that needs it and never logged
     */
    record InstallationSnapshot(
            UUID installationId,
            ProviderCategory category,
            String providerType,
            String environmentCode,
            String baseUrl,
            String status,
            String secretReference,
            String adapterVersion) {}
}
