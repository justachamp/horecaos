package uz.horecaos.platform.ordering.application;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
     * How each of these variants may be quantified, as published (ADR 0137): the one question an
     * amendment and an order read ask about many variants at once, without the modifier groups
     * {@link #forVariant} also resolves.
     *
     * <p>A variant the live publication does not describe, or one that publishes no physical block,
     * is {@link PhysicalRules#WHOLE_UNITS}, exactly as {@link ProductRules#physicalOf} answers for
     * it; it is always present in the result so a caller never has to tell "absent" from "whole".
     * The default asks {@link #forVariant} once per variant; an implementation that can answer in
     * one read overrides it.
     */
    default Map<UUID, PhysicalRules> physicalOf(
            UUID tenantId, UUID brandId, String channelCode, Collection<UUID> variantIds) {
        Map<UUID, PhysicalRules> byVariant = new LinkedHashMap<>();
        for (UUID variantId : variantIds) {
            byVariant.put(
                    variantId,
                    forVariant(tenantId, brandId, channelCode, variantId)
                            .map(rules -> rules.physicalOf(variantId))
                            .orElse(PhysicalRules.WHOLE_UNITS));
        }
        return byVariant;
    }

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

        /**
         * Why this quantity is not one the variant may be ordered in, or empty when it is
         * (ADR 0137). The one place the cart and an amendment ask the question, so a quantity
         * the basket took is a quantity an amendment takes, and the refusal reads the same
         * wherever it is raised.
         *
         * <p>Positive and within the column first, so a client cannot send a quantity the database
         * would round or refuse; then the published rule. The messages name the portion step,
         * because "0.5 is not allowed" tells a customer nothing and "this dish is ordered in
         * steps of 0.5" tells them what to type.
         *
         * @param maximum the largest quantity one line may hold
         */
        public Optional<Refusal> refusalOf(BigDecimal quantity, BigDecimal maximum) {
            if (!Quantities.fitsColumn(quantity) || quantity.compareTo(maximum) > 0) {
                return Optional.of(new Refusal(
                        "QUANTITY_OUT_OF_RANGE",
                        "A quantity is more than zero and at most %s, with at most %d fraction digits"
                                .formatted(maximum.toPlainString(), Quantities.SCALE)));
            }
            if (accepts(quantity)) {
                return Optional.empty();
            }
            if (allowsFraction()) {
                return Optional.of(new Refusal(
                        "QUANTITY_NOT_A_PORTION",
                        "This item is ordered in steps of %s"
                                .formatted(Quantities.plain(Objects.requireNonNull(portionSize)))));
            }
            return Optional.of(new Refusal("FRACTIONAL_QUANTITY_NOT_ALLOWED", "This item is only sold in whole units"));
        }
    }

    /** A quantity a variant cannot be ordered in, with the stable code a client branches on. */
    record Refusal(String code, String message) {}

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
