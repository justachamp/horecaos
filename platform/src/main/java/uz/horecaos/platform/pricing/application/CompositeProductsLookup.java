package uz.horecaos.platform.pricing.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import uz.horecaos.platform.pricing.application.CompositePricing.ComboComponentFact;
import uz.horecaos.platform.pricing.application.CompositePricing.ComboGroupFact;
import uz.horecaos.platform.pricing.application.CompositePricing.HiddenCharge;
import uz.horecaos.platform.pricing.application.CompositePricing.NestedGroupFact;
import uz.horecaos.platform.pricing.application.CompositePricing.OptionFact;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * What pricing needs to know about composite products (ADR 0136), resolved before
 * the engine runs.
 *
 * <p>A port, like {@link CatalogPricingContext}, so pricing depends on facts and not
 * on the catalog's tables. Each method answers one question for the variants and
 * options a cart actually names, in one round trip, with the tenant and brand in the
 * predicate: a variant id is a UUID a client supplied, and a lookup keyed on the id
 * alone would read another brand's combo onto this cart.
 *
 * <p>Only active rows are facts. A combo group, a component or the variant it offers
 * that has been archived is not offered, and the quote says so ({@code
 * COMBO_COMPONENT_NOT_OFFERED}) rather than pricing something withdrawn.
 */
public interface CompositeProductsLookup {

    /**
     * The active groups and components of every combo container among {@code variantIds}.
     * A variant that is not a container is simply absent from the answer.
     */
    ComboCatalog comboCatalog(UUID tenantId, UUID brandId, Set<UUID> variantIds);

    /**
     * The hidden auto-selected modifier options that apply to these variants on this
     * fulfilment mode, by variant.
     *
     * <p>A variant-level attachment of a group wins over the product-level one, so a
     * variant can switch a product's packaging charge off by attaching the same group
     * visibly. An empty list for a variant means nothing is added to it, which is the
     * answer for every variant of a brand that has authored no hidden group.
     *
     * @throws HiddenModifierAmbiguousException when an applicable hidden group is not
     *         required or does not have exactly one active option. The publication
     *         validator blocks such a group, but authoring keeps moving after a menu is
     *         published; with no customer to resolve the choice the quote refuses
     *         instead of picking by row order
     */
    Map<UUID, List<HiddenCharge>> hiddenCharges(
            UUID tenantId, UUID brandId, Set<UUID> variantIds, FulfillmentMode mode);

    /**
     * Facts about the options a line names and about the groups the variants they link
     * offer -- the second level of nesting, and no deeper.
     */
    NestedCatalog nestedCatalog(UUID tenantId, UUID brandId, Set<UUID> optionIds);

    /**
     * A lookup that knows of no composite product, for a caller that prices without a
     * catalog behind it. Every cart it prices is an ordinary one.
     */
    static CompositeProductsLookup none() {
        return new CompositeProductsLookup() {
            @Override
            public ComboCatalog comboCatalog(UUID tenantId, UUID brandId, Set<UUID> variantIds) {
                return ComboCatalog.empty();
            }

            @Override
            public Map<UUID, List<HiddenCharge>> hiddenCharges(
                    UUID tenantId, UUID brandId, Set<UUID> variantIds, FulfillmentMode mode) {
                return Map.of();
            }

            @Override
            public NestedCatalog nestedCatalog(UUID tenantId, UUID brandId, Set<UUID> optionIds) {
                return NestedCatalog.empty();
            }
        };
    }

    /** Active combo structure, keyed so {@link CompositePricing} can resolve a pick without a query. */
    record ComboCatalog(
            Map<UUID, ComboGroupFact> groups,
            Map<UUID, List<UUID>> groupIdsByContainer,
            Map<UUID, ComboComponentFact> components) {

        public static ComboCatalog empty() {
            return new ComboCatalog(Map.of(), Map.of(), Map.of());
        }
    }

    /** Option facts, and the customer-facing groups each linked variant offers. */
    record NestedCatalog(Map<UUID, OptionFact> options, Map<UUID, List<NestedGroupFact>> groupsByVariant) {

        public static NestedCatalog empty() {
            return new NestedCatalog(Map.of(), Map.of());
        }
    }

    /** A hidden group that cannot be applied without guessing. */
    class HiddenModifierAmbiguousException extends RuntimeException {

        private final transient UUID groupId;

        public HiddenModifierAmbiguousException(UUID groupId) {
            super("Hidden modifier group %s is not required or does not have exactly one active option"
                    .formatted(groupId));
            this.groupId = groupId;
        }

        public UUID groupId() {
            return groupId;
        }
    }
}
