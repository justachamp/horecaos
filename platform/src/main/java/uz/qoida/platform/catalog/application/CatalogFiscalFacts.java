package uz.qoida.platform.catalog.application;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.catalog.api.FiscalNodeFacts;
import uz.qoida.platform.catalog.infrastructure.persistence.JdbcCatalogStore;

/**
 * Answers the marking question payments asks while a cart is being priced
 * (ADR 0038).
 *
 * <p>Thin on purpose. The interesting decision is that the question is asked of
 * the catalog at all rather than answered inside a provider adapter, and that is
 * argued in {@link FiscalNodeFacts}; the implementation is one indexed read.
 */
@Component
public class CatalogFiscalFacts implements FiscalNodeFacts {

    private final JdbcCatalogStore store;

    public CatalogFiscalFacts(JdbcCatalogStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> markedNodes(UUID tenantId, UUID brandId, Set<UUID> priceableIds) {
        return store.markedNodes(tenantId, brandId, priceableIds);
    }
}
