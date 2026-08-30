package uz.horecaos.platform.integration.api.provider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves which external account handles a capability at a scope (ADR 0026).
 *
 * <p>Resolution runs up the ancestry: a location binding wins over its brand's,
 * matching the ADR 0030 precedence rule rather than introducing a second one.
 */
public interface ProviderInstallationLookup {

    /** The single provider that should handle this capability here. */
    Optional<BindingRef> primaryBinding(UUID tenantId, UUID brandId, UUID locationId, String capabilityCode);

    /**
     * Every eligible provider, for capabilities where asking several is safe.
     * ADR 0014 quotes multiple couriers this way before booking exactly one.
     */
    List<BindingRef> candidateBindings(UUID tenantId, UUID brandId, UUID locationId, String capabilityCode);

    /** Non-sensitive installation detail. Never a credential. */
    Optional<InstallationSnapshot> installation(UUID tenantId, UUID installationId);

    /**
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
