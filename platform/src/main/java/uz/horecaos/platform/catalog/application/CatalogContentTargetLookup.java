package uz.horecaos.platform.catalog.application;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.content.api.ContentTargetLookup;

/**
 * {@link ContentTargetLookup} over the brand's active publication (ADR 0157, Decision 5).
 *
 * <p>Reads the immutable publication and nothing else, so a product that exists only as a draft is
 * not "in the menu" and a link to it degrades to {@code NONE}: the content read and the menu read
 * cannot disagree about existence. Availability (a dish on the stop list) is deliberately not folded
 * in -- that stays the menu's business.
 */
@Component
public class CatalogContentTargetLookup implements ContentTargetLookup {

    private final JdbcCatalogStore store;

    public CatalogContentTargetLookup(JdbcCatalogStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public Existing existing(
            UUID tenantId,
            UUID brandId,
            String channelCode,
            Collection<UUID> productIds,
            Collection<UUID> categoryIds) {
        if (productIds.isEmpty() && categoryIds.isEmpty()) {
            return Existing.none();
        }
        Optional<UUID> publication = store.findActivePublicationId(tenantId, brandId, channelCode);
        if (publication.isEmpty()) {
            return Existing.none();
        }
        return new Existing(
                store.publicationEntityIds(publication.get(), tenantId, EntityType.PRODUCT, productIds),
                store.publicationEntityIds(publication.get(), tenantId, EntityType.CATEGORY, categoryIds));
    }
}
