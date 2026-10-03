package uz.horecaos.platform.ordering.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.web.api.Quantities;

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

    /**
     * A product's selection rules as published.
     *
     * @param groups every group the product offers, in publication order
     */
    record ProductRules(UUID productId, List<GroupRules> groups, Map<UUID, PhysicalRules> physicalByVariant) {

        public ProductRules {
            physicalByVariant = physicalByVariant == null ? Map.of() : Map.copyOf(physicalByVariant);
        }

        /** A product published before ADR 0137: every variant is a fixed unit sold whole. */
        public ProductRules(UUID productId, List<GroupRules> groups) {
            this(productId, groups, Map.of());
        }

        public Optional<GroupRules> owning(UUID optionId) {
            return groups.stream().filter(group -> group.offers(optionId)).findFirst();
        }

        /**
         * What the publication says about how this variant may be quantified, or
         * {@link PhysicalRules#WHOLE_UNITS} for one that carries no physical block.
         */
        public PhysicalRules physicalOf(UUID variantId) {
            return physicalByVariant.getOrDefault(variantId, PhysicalRules.WHOLE_UNITS);
        }
    }

    /**
     * How a variant may be quantified, as published (ADR 0137).
     *
     * <p>The whole contract for a fractional order: the variant is splittable, it has a
     * portion step, and the quantity is a multiple of that step. A variant with no
     * physical block, or with a block that sets neither, takes whole units exactly as
     * it did when the column was an integer -- widening the type does not by itself
     * invite half a can of soda.
     *
     * @param portionSize the step a splittable variant may be ordered in, or null
     * @param catchweight whether the variant is sold by weight, so that its price is provisional
     *                    until the scale has spoken (ADR 0137)
     */
    record PhysicalRules(boolean splittable, @Nullable BigDecimal portionSize, boolean catchweight) {

        /** A variant that is not sold by weight, which is every one that predates the checkout rule on weighed baskets. */
        public PhysicalRules(boolean splittable, @Nullable BigDecimal portionSize) {
            this(splittable, portionSize, false);
        }

        /** No block published: whole units only. */
        public static final PhysicalRules WHOLE_UNITS = new PhysicalRules(false, null, false);

        public boolean allowsFraction() {
            return splittable && portionSize != null;
        }

        /**
         * Whether this quantity is orderable: always when whole, and when fractional only
         * if the variant allows it and the quantity is a whole number of portions.
         */
        public boolean accepts(BigDecimal quantity) {
            if (Quantities.isWhole(quantity)) {
                return true;
            }
            return allowsFraction() && quantity.remainder(portionSize).signum() == 0;
        }
    }

    /**
     * What one product's attachment says about a group it offers, already resolved against the
     * shared group's own values (ADR 0136).
     */
    record Policy(boolean required, int minimumSelections, int maximumSelections) {}

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

        /**
         * This group as one product uses it (ADR 0136): the product's own required, minimum and
         * maximum in place of the shared group's, everything else unchanged.
         */
        public GroupRules withPolicy(Policy policy) {
            return new GroupRules(
                    groupId,
                    code,
                    policy.required(),
                    policy.minimumSelections(),
                    policy.maximumSelections(),
                    allowSameOptionMultipleTimes,
                    maximumQuantityByOption);
        }

        public int maximumQuantityOf(UUID optionId) {
            return maximumQuantityByOption.getOrDefault(optionId, 1);
        }
    }
}
