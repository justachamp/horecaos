package uz.horecaos.platform.ordering.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The selection rules the published menu states about one product (ADR 0016,
 * ADR 0019).
 *
 * <p>A port rather than a dependency on the catalog module, following
 * {@link OrderCatalogSnapshot} and {@code CatalogPricingContext}: ordering needs
 * the rules a customer was shown, not a menu model it has no business reading.
 *
 * <p>Read from the publication and never from the authoring tables, for the same
 * reason the storefront is: the rules the cart enforces have to be the rules the
 * customer was shown. A draft edited while somebody was choosing must not be able
 * to refuse a basket assembled from what was on screen.
 */
public interface CartMenuRules {

    /**
     * The groups a variant's product offers on this channel, or empty when the
     * live publication does not describe the variant.
     *
     * <p>Empty is not "anything goes" by intention; it is "this menu says nothing
     * about that item". A variant the publication does not carry has no group
     * rules to violate, and it is refused by pricing — which can name it — rather
     * than here.
     */
    Optional<ProductRules> forVariant(UUID tenantId, UUID brandId, String channelCode, UUID variantId);

    /** @param groups every group the product offers, in publication order */
    record ProductRules(UUID productId, List<GroupRules> groups) {

        public Optional<GroupRules> owning(UUID optionId) {
            return groups.stream().filter(group -> group.offers(optionId)).findFirst();
        }
    }

    /**
     * One group as published.
     *
     * @param maximumQuantityByOption the per-option repeat cap, which only means
     *                                anything when {@code allowSameOptionMultipleTimes}
     */
    record GroupRules(
            UUID groupId,
            String code,
            boolean required,
            int minimumSelections,
            int maximumSelections,
            boolean allowSameOptionMultipleTimes,
            Map<UUID, Integer> maximumQuantityByOption) {

        public boolean offers(UUID optionId) {
            return maximumQuantityByOption.containsKey(optionId);
        }

        public int maximumQuantityOf(UUID optionId) {
            return maximumQuantityByOption.getOrDefault(optionId, 1);
        }
    }
}
