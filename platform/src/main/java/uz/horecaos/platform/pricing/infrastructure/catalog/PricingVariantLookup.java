package uz.horecaos.platform.pricing.infrastructure.catalog;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.VariantPricingLookup;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;

/**
 * The real answer to "does this variant have a price" (ADR 0016, ADR 0018).
 *
 * <p>Replaces the stand-in catalog shipped with. Until this bean existed, the
 * publication rule {@code VARIANT_HAS_NO_ACTIVE_PRICE} could not fire and every
 * validation report carried a {@code PRICING_VALIDATION_NOT_WIRED} warning; its
 * presence removes the warning by removing the gap. It is picked up
 * automatically, because the stand-in is declared
 * {@code @ConditionalOnMissingBean}.
 */
@Component
public class PricingVariantLookup implements VariantPricingLookup {

    private final JdbcPricingStore store;
    private final Clock clock;

    public PricingVariantLookup(JdbcPricingStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public Set<UUID> pricedVariants(UUID tenantId, UUID brandId, Set<UUID> variantIds) {
        return store.pricedVariants(tenantId, brandId, variantIds, clock.instant());
    }
}
