package uz.horecaos.platform.catalog.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.UnlistedOfferingsPort;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;

/**
 * The {@code catalog.api} face of the backfill reads (gap-map row 4.1),
 * matching {@link StopListPortAdapter}'s own shape: a translation layer only,
 * over {@link JdbcCatalogStore#unlistedAvailableVariantsAtLocation} and
 * {@link JdbcCatalogStore#unlistedLocationsForVariant}.
 */
@Component
public class UnlistedOfferingsPortAdapter implements UnlistedOfferingsPort {

    private final JdbcCatalogStore store;

    public UnlistedOfferingsPortAdapter(JdbcCatalogStore store) {
        this.store = store;
    }

    @Override
    public List<UUID> unlistedAvailableVariantsAtLocation(UUID tenantId, UUID brandId, UUID locationId, int limit) {
        return store.unlistedAvailableVariantsAtLocation(tenantId, brandId, locationId, null, limit).stream()
                .map(JdbcCatalogStore.UnlistedVariantRow::variantId)
                .toList();
    }

    @Override
    public List<UUID> unlistedLocationsForVariant(UUID tenantId, UUID brandId, UUID variantId, int limit) {
        return store.unlistedLocationsForVariant(tenantId, brandId, variantId, limit);
    }
}
