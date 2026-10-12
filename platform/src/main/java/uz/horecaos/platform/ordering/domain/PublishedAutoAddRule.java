package uz.horecaos.platform.ordering.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One auto-add rule as the publication carries it (ADR 0158).
 *
 * <p>An immutable value: the cart enforces what the customer was shown, so it reads rules from
 * the published menu and never from the authoring tables, and a rule edited and not republished
 * changes nothing for a customer. {@link #version} is the rule's version at publication and is
 * what an injected line records, so an order names the exact rule that put a line there.
 *
 * <p>Pure: no Spring, no SQL, no clock. The console's simulator and the cart run the same
 * {@link AutoAddEvaluator} over the same value.
 *
 * @param addVariantId     PLAIN and PRODUCT_TRIGGERED: the variant added; null for PORTION_BAND,
 *                         whose bands name the variant
 * @param quantity         PLAIN: the quantity; PRODUCT_TRIGGERED: the quantity per step
 * @param triggerThreshold PRODUCT_TRIGGERED: the units that make one step
 * @param locationIds      empty means every location of the brand
 * @param customerText     the text shown beside the injected line, by locale
 */
public record PublishedAutoAddRule(
        UUID ruleId,
        int version,
        String code,
        Kind kind,
        Mode mode,
        @Nullable UUID addVariantId,
        @Nullable BigDecimal quantity,
        @Nullable BigDecimal triggerThreshold,
        boolean repeatPerMultiple,
        BigDecimal minimumQuantity,
        BigDecimal maximumQuantity,
        boolean mandatory,
        Set<String> fulfillmentModes,
        Set<String> channelSystemTypes,
        Set<UUID> locationIds,
        List<Trigger> triggers,
        List<Band> bands,
        Map<String, String> customerText) {

    /** The three kinds the record decides. There is no scripted or free-form kind. */
    public enum Kind {
        PLAIN,
        PRODUCT_TRIGGERED,
        PORTION_BAND
    }

    /** Whether the rule writes a line or suggests one. */
    public enum Mode {
        INJECT,
        OFFER
    }

    /** A node whose units a PRODUCT_TRIGGERED rule counts: exactly one of the three is set. */
    public record Trigger(
            @Nullable UUID variantId,
            @Nullable UUID productId,
            @Nullable UUID categoryId) {

        public Trigger {
            int set = (variantId == null ? 0 : 1) + (productId == null ? 0 : 1) + (categoryId == null ? 0 : 1);
            if (set != 1) {
                throw new IllegalArgumentException("A trigger names exactly one of a variant, a product or a category");
            }
        }

        boolean matches(BasketFacts.Line line) {
            if (variantId != null) {
                return variantId.equals(line.variantId());
            }
            if (productId != null) {
                return productId.equals(line.productId());
            }
            return line.categoryIds().contains(categoryId);
        }
    }

    /** A portion band: {@code from <= portions < to}, {@code to} null meaning no upper bound. */
    public record Band(
            BigDecimal portionsFrom, @Nullable BigDecimal portionsTo, UUID addVariantId, BigDecimal quantity) {

        boolean contains(BigDecimal portions) {
            return portions.compareTo(portionsFrom) >= 0 && (portionsTo == null || portions.compareTo(portionsTo) < 0);
        }
    }

    public PublishedAutoAddRule {
        Objects.requireNonNull(ruleId, "A rule has an id");
        Objects.requireNonNull(kind, "A rule has a kind");
        Objects.requireNonNull(mode, "A rule has a mode");
        fulfillmentModes = Set.copyOf(fulfillmentModes);
        channelSystemTypes = Set.copyOf(channelSystemTypes);
        locationIds = Set.copyOf(locationIds);
        triggers = List.copyOf(triggers);
        bands = List.copyOf(bands);
        customerText = Map.copyOf(customerText);
    }

    /** Whether the rule applies to this order's context at all. */
    boolean appliesTo(AutoAddEvaluator.Context context) {
        return fulfillmentModes.contains(context.fulfillmentMode())
                && channelSystemTypes.contains(context.channelSystemType())
                && (locationIds.isEmpty()
                        || (context.locationId() != null && locationIds.contains(context.locationId())));
    }
}
