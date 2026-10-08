package uz.horecaos.platform.ordering.application;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule;

/**
 * The auto-add rules the published menu states, and the facts about a basket's variants a rule needs
 * (ADR 0158).
 *
 * <p>A port rather than a dependency on the catalog module, following {@link CartMenuRules}: ordering
 * needs the rules a customer was shown and nothing of how they are authored. Read from the live
 * publication of the cart's channel and never from the authoring tables, so a rule edited and not
 * republished changes nothing for a customer, and the rule a cart line names (by id and version) is
 * the one on screen.
 *
 * <p>Each channel's publication carries the rules whose channel types include that channel's system
 * type, so no filtering by channel is left to the cart.
 */
public interface CartAutoAddRules {

    /** The rules the channel's live publication carries, or none when there is no live publication. */
    List<PublishedAutoAddRule> rulesFor(UUID tenantId, UUID brandId, String channelCode);

    /**
     * What the live publication says about each of these variants: its product, that product's
     * categories and what one unit is worth in portions. A variant the publication does not carry is
     * absent from the result, which is how the reconciler knows it is not on the menu.
     */
    Map<UUID, VariantFacts> variantFacts(UUID tenantId, UUID brandId, String channelCode, Collection<UUID> variantIds);

    /**
     * The component variant and per-pick unit count behind each combo component id, so a combo line
     * counts as the component lines it puts on the order (ADR 0136).
     */
    Map<UUID, ComboComponentFacts> comboComponents(
            UUID tenantId, UUID brandId, String channelCode, Collection<UUID> componentIds);

    /**
     * @param productId       null when the publication does not place the variant in a product
     * @param portionsPerUnit null when the variant states none, which counts as zero
     */
    record VariantFacts(
            @Nullable UUID productId,
            Set<UUID> categoryIds,
            @Nullable BigDecimal portionsPerUnit) {

        public VariantFacts {
            categoryIds = Set.copyOf(categoryIds);
        }
    }

    /** One combo component: the variant it puts on the order and the units one pick of it is worth. */
    record ComboComponentFacts(UUID variantId, int defaultQuantity) {}

    /** For a cart built without a publication to read: no rule, so the reconciler has nothing to do. */
    CartAutoAddRules NONE = new CartAutoAddRules() {
        @Override
        public List<PublishedAutoAddRule> rulesFor(UUID tenantId, UUID brandId, String channelCode) {
            return List.of();
        }

        @Override
        public Map<UUID, VariantFacts> variantFacts(
                UUID tenantId, UUID brandId, String channelCode, Collection<UUID> variantIds) {
            return Map.of();
        }

        @Override
        public Map<UUID, ComboComponentFacts> comboComponents(
                UUID tenantId, UUID brandId, String channelCode, Collection<UUID> componentIds) {
            return Map.of();
        }
    };
}
