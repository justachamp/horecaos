package uz.qoida.platform.catalog.application;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uz.qoida.platform.catalog.api.VariantPricingLookup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies a {@link VariantPricingLookup} until the pricing module exists
 * (ADR 0016).
 *
 * <p>It reports every variant as priced, which means the
 * {@code VARIANT_HAS_NO_ACTIVE_PRICE} rule does not actually fire yet. That is a
 * real gap, so it is made loud rather than quiet in three ways: a warning at
 * startup, a {@code PRICING_VALIDATION_NOT_WIRED} warning on every validation
 * report, and {@link ConditionalOnMissingBean} so the moment pricing ships a real
 * implementation this one disappears with no code change.
 *
 * <p>Failing closed instead — reporting nothing as priced — was considered and
 * rejected: it would block every publication, which does not make the gap
 * safer, only invisible behind an unrelated error.
 */
@Configuration
public class CatalogPricingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CatalogPricingConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(VariantPricingLookup.class)
    VariantPricingLookup unwiredVariantPricingLookup() {
        log.warn("No pricing module is wired: catalog publication cannot verify that variants "
                + "have active prices. Every validation report will carry PRICING_VALIDATION_NOT_WIRED.");
        return new VariantPricingLookup() {
            @Override
            public Set<UUID> pricedVariants(UUID tenantId, UUID brandId, Set<UUID> variantIds) {
                return variantIds;
            }

            @Override
            public boolean isWired() {
                return false;
            }
        };
    }
}
