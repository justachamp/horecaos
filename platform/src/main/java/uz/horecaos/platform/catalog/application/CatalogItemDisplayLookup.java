package uz.horecaos.platform.catalog.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.ItemDisplayLookup;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;

/** {@link ItemDisplayLookup} over catalog's own read model. */
@Component
public class CatalogItemDisplayLookup implements ItemDisplayLookup {

    private final JdbcCatalogStore store;

    public CatalogItemDisplayLookup(JdbcCatalogStore store) {
        this.store = store;
    }

    @Override
    public Optional<String> displayName(UUID tenantId, UUID variantId) {
        return store.productNameFor(tenantId, variantId);
    }
}
