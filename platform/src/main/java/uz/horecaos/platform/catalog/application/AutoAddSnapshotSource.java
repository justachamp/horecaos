package uz.horecaos.platform.catalog.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.CatalogConfigurationKeys;
import uz.horecaos.platform.catalog.api.PromotionGiftLookup;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcAutoAddRuleStore;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * Reads what the publication snapshot needs about auto-add rules (ADR 0158): the active rules, the gift
 * variants of active promotions, and the tenant's legal switch for mandatory rules.
 *
 * <p>A seam of its own so {@link CatalogSnapshotLoader} keeps the constructor its callers and tests
 * already use; a loader built without one publishes no rules, which is exactly what a catalog that never
 * authored one wants.
 */
public interface AutoAddSnapshotSource {

    CatalogValidator.AutoAddContext load(UUID tenantId, UUID brandId);

    /** For a loader with no auto-add rules to read. */
    AutoAddSnapshotSource NONE = (tenantId, brandId) -> CatalogValidator.AutoAddContext.empty();

    /** The production source: the rule tables, pricing's promotions and the ADR 0030 switch. */
    @Component
    class Stored implements AutoAddSnapshotSource {

        private final JdbcAutoAddRuleStore rules;
        private final PromotionGiftLookup gifts;
        private final ConfigurationResolver configuration;

        public Stored(JdbcAutoAddRuleStore rules, PromotionGiftLookup gifts, ConfigurationResolver configuration) {
            this.rules = rules;
            this.gifts = gifts;
            this.configuration = configuration;
        }

        @Override
        public CatalogValidator.AutoAddContext load(UUID tenantId, UUID brandId) {
            var active = rules.active(tenantId, brandId);
            if (active.isEmpty()) {
                // Nothing to publish and nothing to validate: neither lookup is worth a query.
                return CatalogValidator.AutoAddContext.empty();
            }
            Boolean allowed = configuration.value(
                    CatalogConfigurationKeys.AUTO_ADD_MANDATORY_ALLOWED, ResourceScope.tenant(tenantId));
            return new CatalogValidator.AutoAddContext(
                    active, gifts.giftVariantIds(tenantId, brandId), Boolean.TRUE.equals(allowed));
        }
    }
}
