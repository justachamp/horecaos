package uz.horecaos.platform.catalog.api;

import java.util.Set;
import java.util.UUID;

/**
 * Which variants have an active price (ADR 0016).
 *
 * <p>Catalog owns the rule that an unpriced variant cannot be published; pricing
 * owns the fact of what a price is. The port keeps both true, so neither module
 * has to hold the other's data.
 *
 * <p>It lives in catalog's public interface because pricing implements it. The
 * consumer declaring the contract is what keeps the dependency pointing one way:
 * catalog never learns what a price book is.
 */
public interface VariantPricingLookup {

    /** The subset of {@code variantIds} that currently have an active price. */
    Set<UUID> pricedVariants(UUID tenantId, UUID brandId, Set<UUID> variantIds);

    /**
     * The subset of combo component ids (ADR 0136) that currently have an active
     * {@code COMBO_COMPONENT} price.
     *
     * <p>Defaults to none, so a stand-in that knows nothing about combos reports
     * every component unpriced rather than quietly passing it: the validator
     * blocks on a component with no price, and an implementation that predates
     * combos must not be the reason one reaches a customer.
     */
    default Set<UUID> pricedComboComponents(UUID tenantId, UUID brandId, Set<UUID> componentIds) {
        return Set.of();
    }

    /**
     * Whether this implementation actually consults pricing data.
     *
     * <p>Exists so the stand-in used before the pricing module ships can say so,
     * and every validation report can carry a warning that one of its checks did
     * not really run. A real implementation inherits {@code true} and needs to do
     * nothing.
     */
    default boolean isWired() {
        return true;
    }
}
