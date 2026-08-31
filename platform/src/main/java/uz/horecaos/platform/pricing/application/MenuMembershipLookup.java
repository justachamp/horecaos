package uz.horecaos.platform.pricing.application;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which product a variant belongs to, and which categories that product is in
 * (ADR 0018 stages 3 and 4).
 *
 * <p>A port, and a deliberately narrow one. {@link PromotionEvaluator} has to
 * answer "is this line a MAINS item" without pricing depending on the catalog
 * module, and this is the only catalog fact promotions need that
 * {@link CatalogPricingContext} does not already carry. It is kept separate
 * rather than folded into that interface because the two are read at different
 * moments for different reasons — one prices a quote, one decides whether an
 * offer matches — and a caller that needs neither should not have to stub both.
 *
 * <p>Implemented inside pricing, against the catalog schema, exactly as
 * {@code CatalogPricingContext} is. Pricing reads catalog <em>tables</em>; it
 * never imports catalog <em>types</em>, which is what keeps the module graph
 * acyclic while both modules still need facts from each other.
 *
 * <p><strong>Membership is authoring state, not publication state, and that is a
 * real limitation.</strong> A promotion evaluated against a category reads the
 * category a product is in now, while the quote around it is priced against an
 * immutable publication. Moving a dish between categories therefore changes
 * which promotions match it before the menu is republished. The alternative —
 * reading membership out of the publication — is the right answer and needs
 * {@code publication_items} to carry it for products as well as categories;
 * until then this is stated rather than hidden.
 */
public interface MenuMembershipLookup {

    /**
     * The product and categories behind each variant, in one round trip.
     *
     * @param variantIds the variants on the cart. An empty set is answered with
     *        an empty map and no query.
     * @return an entry per variant that resolved. A variant absent from the
     *         result is one the brand does not own, and a promotion condition
     *         reading it simply does not match — which is the safe direction: a
     *         missing membership must never make an offer apply to everything.
     */
    Map<UUID, Membership> membershipOf(UUID tenantId, UUID brandId, Set<UUID> variantIds);

    /**
     * The product a variant belongs to, and its direct categories.
     *
     * @param categoryIds every category the product sits in, direct only.
     */
    record Membership(UUID productId, Set<UUID> categoryIds) {

        public Membership {
            categoryIds = categoryIds == null ? Set.of() : Set.copyOf(categoryIds);
        }
    }
}
