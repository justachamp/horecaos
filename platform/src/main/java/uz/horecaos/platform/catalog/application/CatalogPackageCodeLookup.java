package uz.horecaos.platform.catalog.application;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.api.PackageCodeLookup;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;

/**
 * Answers the package-code question a POS export asks while building one order
 * line (ADR 0038, docs/providers/clopos-api.md Q12).
 *
 * <p>Thin, the same way {@link CatalogFiscalFacts} is: the interesting decision
 * is that the question is asked of the catalog at all rather than reinvented as
 * a second classification field, and that is argued in {@link PackageCodeLookup}.
 */
@Component
public class CatalogPackageCodeLookup implements PackageCodeLookup {

    private final JdbcCatalogStore store;

    public CatalogPackageCodeLookup(JdbcCatalogStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> packageCodes(UUID tenantId, UUID brandId, Set<UUID> priceableIds) {
        return store.packageCodes(tenantId, brandId, priceableIds);
    }
}
