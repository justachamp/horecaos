package uz.horecaos.platform.catalog.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The authoring model (ADR 0016).
 *
 * <p>Grouped in one file because these are data carriers read together; the
 * behaviour lives in the validator and the publication service.
 *
 * <p>Note what is absent: no price, no stock count, no provider identifier.
 * Pricing owns money, inventory owns quantity, and ADR 0026 owns external
 * mappings. A catalog row that carried a price would create a second place for
 * money to live, with no rule for which one wins.
 */
public final class CatalogEntities {

    private CatalogEntities() {}

    public enum Status {
        DRAFT,
        ACTIVE,
        ARCHIVED
    }

    public enum EntityType {
        CATALOG,
        CATEGORY,
        PRODUCT,
        VARIANT,
        MODIFIER_GROUP,
        MODIFIER_OPTION,
        /**
         * A charge that reaches a receipt as an ordinary line without being a
         * catalog item — today only the delivery fee (ADR 0038).
         *
         * <p>It names the target of a validation finding and nothing else. A fee
         * has no translations and no media, so the two schema checks that
         * enumerate entity types deliberately do not list it.
         */
        FEE
    }

    /**
     * ADR 0018's vocabulary for the three things that can carry a price, and
     * therefore the three things that can appear on a receipt line.
     *
     * <p>Fiscal classification uses exactly this vocabulary (ADR 0038), so the
     * delivery fee is classified by the same mechanism as a dish rather than by
     * a second one that will be forgotten.
     */
    public enum PriceableType {
        VARIANT,
        MODIFIER_OPTION,
        FEE
    }

    /** One thing that can be priced, and therefore one thing that can be classified. */
    public record PriceableNode(PriceableType type, UUID id) {

        public static PriceableNode variant(UUID id) {
            return new PriceableNode(PriceableType.VARIANT, id);
        }

        public static PriceableNode modifierOption(UUID id) {
            return new PriceableNode(PriceableType.MODIFIER_OPTION, id);
        }

        public static PriceableNode fee(UUID id) {
            return new PriceableNode(PriceableType.FEE, id);
        }
    }

    /**
     * A charge a brand makes that is not a catalog item (ADR 0038).
     *
     * <p>It exists so the delivery charge has a stable identity to be classified
     * under. Deliberately no amount: the amount is ADR 0037's, resolved per
     * order from a tariff, and a second place for a fee to have a price would be
     * a second answer to what the customer pays.
     */
    public record Fee(UUID id, UUID tenantId, UUID brandId, String code, Status status, int version) {

        /** The only fee any brand has today. */
        public static final String DELIVERY = "DELIVERY";
    }

    public enum OfferingStatus {
        /** Orderable now. */
        AVAILABLE,
        /** Shown but not orderable — "sold out" rather than absent. */
        UNAVAILABLE,
        /** Not shown at all. */
        HIDDEN
    }

    public record Catalog(UUID id, UUID tenantId, UUID brandId, String code, String name, Status status, int version) {}

    /**
     * A dish, as opposed to a thing that can be ordered.
     *
     * <p>It carries no fiscal classification. V0021 put a default here for its
     * variants to inherit; V0028 ends that, because a product is not priceable
     * and never reaches a receipt as its own line, so resolving a code through a
     * parent means the resolver needs a row the published snapshot does not
     * contain. Classification attaches to the priceable node instead.
     */
    public record Product(UUID id, UUID tenantId, UUID brandId, String code, Status status, int version) {}

    /**
     * A sellable variation of a product.
     *
     * @param unitCode the measurement unit a menu is authored in — not the numeric fiscal unit
     */
    public record Variant(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID productId,
            @Nullable String sku,
            String unitCode,
            boolean isDefault,
            int sortOrder,
            Status status,
            int version) {}

    public record Category(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID catalogId,
            @Nullable UUID parentCategoryId,
            String code,
            int sortOrder,
            Status status,
            int version) {}

    /**
     * A set of modifier options offered together, such as a size or topping choice.
     *
     * @param minimumSelections how many the customer must choose
     * @param maximumSelections how many they may choose
     */
    public record ModifierGroup(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String code,
            boolean required,
            int minimumSelections,
            int maximumSelections,
            boolean allowSameOptionMultipleTimes,
            int sortOrder,
            Status status,
            int version) {}

    /**
     * One choice within a {@link ModifierGroup}.
     *
     * @param linkedVariantId set when the modifier is itself something sellable,
     *                        in which case its classification falls back to that
     *                        variant's rather than being entered twice
     */
    public record ModifierOption(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID modifierGroupId,
            String code,
            @Nullable UUID linkedVariantId,
            int maximumQuantity,
            int sortOrder,
            Status status,
            int version) {}

    public record Translation(
            EntityType entityType,
            UUID entityId,
            String locale,
            String name,
            @Nullable String description) {}

    public record LocationOffering(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID variantId,
            OfferingStatus status,
            List<String> fulfillmentModes,
            int version) {}

    /** One entity as it was at publication time. Never a reference to a live row. */
    public record PublicationItem(
            EntityType entityType, UUID entityId, int entityVersion, Map<String, Object> content) {}
}
