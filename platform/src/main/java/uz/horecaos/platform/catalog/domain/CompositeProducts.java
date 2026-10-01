package uz.horecaos.platform.catalog.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierGroup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;

/**
 * Composite products: combo groups and modifier depth (ADR 0136).
 *
 * <p>Data carriers and the few pure rules that belong with them, in one file for
 * the reason {@link CatalogEntities} is. Nothing here prices anything: a combo's
 * money is a {@code pricing.prices} row of type {@code COMBO_COMPONENT}, and a
 * catalog record that carried an amount would be a second place for money to
 * live with no rule for which one wins.
 */
public final class CompositeProducts {

    private CompositeProducts() {}

    /**
     * Whether an attached modifier group is a choice the customer makes or a
     * charge the server applies.
     *
     * <p>{@link #HIDDEN_AUTO_SELECT} exists for packaging: a delivery box that
     * always reaches the receipt of a {@code DELIVERY} order without the customer
     * being shown a group. It is a column and not a free-text tag because a typed
     * tag cannot be validated, cannot drive the fulfilment-mode filter
     * deterministically, and silently diverges in spelling between brands.
     */
    public enum Visibility {
        VISIBLE,
        HIDDEN_AUTO_SELECT
    }

    /**
     * The fulfilment vocabulary an attachment filters on -- {@code
     * location_offerings}' own, so one word means one thing across the catalog.
     */
    public enum FulfillmentMode {
        DELIVERY,
        PICKUP,
        DINE_IN
    }

    /** Which table an attachment row lives in. */
    public enum AttachmentOwnerType {
        PRODUCT,
        VARIANT
    }

    /**
     * One choice a combo asks for.
     *
     * @param containerVariantId the sellable "Комбо №1". Never priced and never
     *                           sold directly
     * @param minimumSelections  how many picks the customer must make in this group
     * @param maximumSelections  how many they may make
     * @param allowSameComponentMultipleTimes whether one component may be picked
     *                           more than once ("two of the same drink")
     */
    public record ComboGroup(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID containerVariantId,
            String code,
            int minimumSelections,
            int maximumSelections,
            boolean allowSameComponentMultipleTimes,
            int sortOrder,
            Status status,
            int version) {

        /**
         * How many picks this group can actually collect, given its active
         * components.
         *
         * <p>The database constrains {@code minimum <= maximum} but not against
         * the number of components that exist, so "choose 3 of 2" passes the
         * check and still traps the customer. The same arithmetic as a modifier
         * group's, with one difference: a component carries no per-option cap, so
         * where repeats are allowed the capacity is the group's own maximum --
         * provided at least one component exists to repeat.
         */
        public int selectableCapacity(int activeComponents) {
            if (activeComponents <= 0) {
                return 0;
            }
            return allowSameComponentMultipleTimes ? maximumSelections : activeComponents;
        }
    }

    /**
     * A real variant offered inside a {@link ComboGroup}.
     *
     * @param defaultQuantity units one pick of this component puts on the order;
     *                        its price is per unit
     */
    public record ComboComponent(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID comboGroupId,
            UUID componentVariantId,
            int defaultQuantity,
            int sortOrder,
            Status status,
            int version) {}

    /**
     * A modifier group attached to a product or a variant, with the three things
     * the attachment itself may say that the shared group does not (ADR 0136).
     *
     * <p>The shared {@link ModifierGroup} is never edited from here, which is the
     * rule {@code catalog.md} states and the reason these are overrides on the
     * join row: a second product attaching the same group is untouched by the
     * first product's choice.
     *
     * @param ownerId        the product or the variant, per {@code ownerType}
     * @param modes          null = every fulfilment mode; else the subset a
     *                       {@link Visibility#HIDDEN_AUTO_SELECT} attachment
     *                       applies to. Always null on a visible attachment
     * @param requiredOverride null falls back to the group's own {@code required}
     * @param minimumOverride  null falls back to the group's own minimum
     * @param maximumOverride  null falls back to the group's own maximum
     */
    public record ModifierAttachment(
            UUID tenantId,
            UUID brandId,
            AttachmentOwnerType ownerType,
            UUID ownerId,
            UUID modifierGroupId,
            int sortOrder,
            Visibility visibility,
            @Nullable Set<FulfillmentMode> modes,
            @Nullable Boolean requiredOverride,
            @Nullable Integer minimumOverride,
            @Nullable Integer maximumOverride,
            int version) {

        public ModifierAttachment {
            modes = modes == null ? null : Set.copyOf(modes);
        }

        public boolean hidden() {
            return visibility == Visibility.HIDDEN_AUTO_SELECT;
        }

        /** Whether this attachment applies on an order of the given fulfilment mode. */
        public boolean appliesTo(FulfillmentMode mode) {
            return modes == null || modes.contains(mode);
        }

        public boolean effectiveRequired(ModifierGroup group) {
            return requiredOverride != null ? requiredOverride : group.required();
        }

        public int effectiveMinimum(ModifierGroup group) {
            return minimumOverride != null ? minimumOverride : group.minimumSelections();
        }

        public int effectiveMaximum(ModifierGroup group) {
            return maximumOverride != null ? maximumOverride : group.maximumSelections();
        }

        /**
         * Why the effective range -- this attachment laid over the group's own
         * values -- cannot be completed, or null when it can.
         *
         * <p>The row's own constraints cannot see the group, and the group can
         * move after an override was written (its minimum raised above the
         * override's maximum), so this is asked at write time and again at
         * publication.
         */
        public @Nullable String rangeProblem(ModifierGroup group) {
            int minimum = effectiveMinimum(group);
            int maximum = effectiveMaximum(group);
            if (minimum > maximum) {
                return "The effective minimum %d is above the effective maximum %d".formatted(minimum, maximum);
            }
            if (effectiveRequired(group) && minimum < 1) {
                return "A required group needs a minimum of at least one selection";
            }
            return null;
        }
    }

    /**
     * The groups a variant is offered with: its product's attachments, with the
     * variant's own laid over them.
     *
     * <p>A variant-level attachment of the same group wins, so a "Large" variant
     * can make a size group required where the product's other variants do not.
     * Ordered by sort order then group id so the answer never depends on row
     * order -- the same determinism the pricing engine holds itself to.
     */
    public static List<ModifierAttachment> effectiveAttachments(
            Collection<ModifierAttachment> productLevel, Collection<ModifierAttachment> variantLevel) {
        Map<UUID, ModifierAttachment> byGroup = new LinkedHashMap<>();
        productLevel.forEach(attachment -> byGroup.put(attachment.modifierGroupId(), attachment));
        variantLevel.forEach(attachment -> byGroup.put(attachment.modifierGroupId(), attachment));
        return byGroup.values().stream()
                .sorted(java.util.Comparator.comparingInt(ModifierAttachment::sortOrder)
                        .thenComparing(ModifierAttachment::modifierGroupId))
                .toList();
    }
}
