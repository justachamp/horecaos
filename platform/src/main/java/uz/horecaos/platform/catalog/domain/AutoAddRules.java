package uz.horecaos.platform.catalog.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;

/**
 * The authoring model of cart injection (ADR 0158): an auto-add rule, its trigger nodes, its
 * portion bands and the locations it is limited to.
 *
 * <p>Grouped in one file like {@link CompositeProducts}: these are data carriers read together,
 * and the behaviour lives in the authoring service, the validator and the snapshot loader. The
 * cart never reads these; it reads the published copy ({@code AUTO_ADD_RULE} publication items).
 */
public final class AutoAddRules {

    private AutoAddRules() {}

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

    /** The fulfilment modes a rule may name (ADR 0036). */
    public static final Set<String> FULFILLMENT_MODES = Set.of("DELIVERY", "PICKUP", "DINE_IN");

    /**
     * The sales-channel system types a rule may name: the cart-built ones only. An aggregator order
     * arrives with its lines and total (ADR 0040) and a POS order is a ticket the POS keeps editing
     * (ADR 0047), so neither can be stored (ADR 0158 Decision 12; the database CHECK says the same).
     */
    public static final Set<String> CART_BUILT_CHANNEL_TYPES =
            Set.of("WEB", "IOS", "ANDROID", "TELEGRAM", "KIOSK", "QR_TABLE", "CALL_CENTRE");

    /** The publication item type that carries a rule to the cart. */
    public static final String PUBLICATION_ENTITY_TYPE = "AUTO_ADD_RULE";

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
    }

    /** A portion band: {@code from <= portions < to}, {@code to} null meaning no upper bound. */
    public record Band(
            BigDecimal portionsFrom, @Nullable BigDecimal portionsTo, UUID addVariantId, BigDecimal quantity) {}

    /**
     * One rule with the children a PUT replaces.
     *
     * @param addVariantId PLAIN and PRODUCT_TRIGGERED: the variant added; null for PORTION_BAND
     * @param quantity     PLAIN: the quantity; PRODUCT_TRIGGERED: per step
     * @param locationIds  empty means every location of the brand
     */
    public record AutoAddRule(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String code,
            Kind kind,
            Mode mode,
            Status status,
            @Nullable UUID addVariantId,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal triggerThreshold,
            boolean repeatPerMultiple,
            BigDecimal minimumQuantity,
            BigDecimal maximumQuantity,
            boolean mandatory,
            Set<String> fulfillmentModes,
            Set<String> channelSystemTypes,
            int sortOrder,
            int version,
            List<Trigger> triggers,
            List<Band> bands,
            Set<UUID> locationIds) {

        public AutoAddRule {
            fulfillmentModes = Set.copyOf(fulfillmentModes);
            channelSystemTypes = Set.copyOf(channelSystemTypes);
            triggers = List.copyOf(triggers);
            bands = List.copyOf(bands);
            locationIds = Set.copyOf(locationIds);
        }

        /** Every variant this rule can add: its own, or those of its bands. */
        public Set<UUID> addedVariantIds() {
            java.util.LinkedHashSet<UUID> ids = new java.util.LinkedHashSet<>();
            if (addVariantId != null) {
                ids.add(addVariantId);
            }
            bands.forEach(band -> ids.add(band.addVariantId()));
            return ids;
        }
    }
}
