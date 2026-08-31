package uz.horecaos.platform.catalog.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The name a variant is sold under, for a consumer that only needs to name
 * an item in a message and never needs the rest of catalog's authoring
 * model.
 *
 * <p>The direct-direction port catalog.api has not needed before this:
 * {@link MenuPriceLookup} and {@link VariantPricingLookup} are catalog
 * asking another module a question, implemented by that module (pricing).
 * This one is another module asking catalog, implemented by catalog itself
 * — the same shape {@code ordering.api.OrderDirectory} already has for
 * {@code notifications}, just declared in this module's own {@code api}
 * package rather than a second module's.
 */
public interface ItemDisplayLookup {

    /**
     * @return empty when the variant does not resolve to a product, or the
     *         product carries no translation in any locale
     */
    Optional<String> displayName(UUID tenantId, UUID variantId);
}
