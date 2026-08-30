package uz.qoida.platform.integration.api.provider;

import java.util.Optional;
import java.util.UUID;

/**
 * The single store of external identifier mappings (ADR 0026).
 *
 * <p>Catalog, inventory, ordering, and fulfilment read through this port and
 * keep no local copy. ADR 0016 originally proposed a second mapping table; two
 * stores for one fact have no defined winner when they disagree.
 */
public interface ProviderEntityMappingLookup {

    Optional<String> externalIdFor(UUID bindingId, String entityType, UUID qoidaEntityId);

    Optional<UUID> qoidaIdFor(UUID bindingId, String entityType, String externalId);
}
