package uz.horecaos.platform.pricing.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeOutcome;

/**
 * A priced cart as stored, in the shape an order copies from (ADR 0018).
 *
 * <p>Amounts are integer minor units and a currency, never a decimal type: the
 * cutover rules make that platform-wide, and a floating point som is a rounding
 * argument with a customer.
 *
 * <p>The line descriptions here are already snapshots taken at pricing time. An
 * order copies them rather than re-reading the menu, so a dish renamed next
 * month does not change what last week's receipt says was bought.
 *
 * @param customerAccountId null for a guest cart, which has no account to price
 *                          loyalty or account-scoped terms against
 * @param deliveryOutcome   ADR 0037: how delivery-fee resolution ended, or null
 *                          when it was never attempted — a collected cart, or a
 *                          delivery cart with no destination yet. {@code
 *                          feeMinor} is the amount actually charged; this is
 *                          whether a checkout may rely on it. Only {@link
 *                          DeliveryFeeOutcome#RESOLVED} and {@link
 *                          DeliveryFeeOutcome#EXTERNALLY_PRICED} are a fee a
 *                          checkout may accept — every other value is a refusal
 *                          a zero {@code feeMinor} cannot be told apart from
 * @param deliveryShortfallMinor how far the goods subtotal sits below the
 *                          zone's minimum basket, or null when it clears it, no
 *                          minimum applies, or delivery was never resolved.
 *                          Non-null even when {@code deliveryOutcome} is {@code
 *                          RESOLVED}: the minimum is a checkout precondition
 *                          the resolver does not enforce itself
 * @param deliveryMinBasketMinor the zone's minimum basket, or null when it sets
 *                          none, for a storefront to render "minimum basket X"
 * @param deliveryFreeFromMinor the zone's free-delivery threshold, or null
 */
public record QuoteSnapshot(
        UUID quoteId,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        @Nullable UUID customerAccountId,
        String currency,
        Status status,
        UUID catalogPublicationId,
        String contextHash,
        long subtotalMinor,
        long taxMinor,
        long feeMinor,
        long discountMinor,
        long totalMinor,
        Instant expiresAt,
        List<Line> lines,
        List<Adjustment> adjustments,
        @Nullable DeliveryFeeOutcome deliveryOutcome,
        @Nullable Long deliveryShortfallMinor,
        @Nullable Long deliveryMinBasketMinor,
        @Nullable Long deliveryFreeFromMinor) {

    public enum Status {
        ACTIVE,
        ACCEPTED,
        EXPIRED,
        SUPERSEDED
    }

    public QuoteSnapshot {
        lines = List.copyOf(lines);
        adjustments = List.copyOf(adjustments);
    }

    /**
     * Whether {@code feeMinor} is a delivery charge a checkout may accept.
     *
     * <p>False for a cart never priced as a delivery ({@code deliveryOutcome}
     * null) exactly as it is for a refusal outcome — both leave {@code
     * feeMinor} at zero, and neither zero is a fee anybody agreed to.
     */
    public boolean isDeliveryFeeUsable() {
        return deliveryOutcome == DeliveryFeeOutcome.RESOLVED
                || deliveryOutcome == DeliveryFeeOutcome.EXTERNALLY_PRICED;
    }

    /**
     * One item line of a priced cart.
     *
     * @param lineKey the cart's stable line key, so lines match up without relying on order.
     *                A combo's component lines carry the cart line's key followed by
     *                {@code ~} and a position, so each is stable on its own and all of them
     *                sort next to the line they came from
     * @param comboSelectionId ADR 0136: groups the component lines of one combo purchase,
     *                null on every other line. An order copies it onto each component
     *                order line, and a report counts distinct values to know how many
     *                combos were sold
     * @param comboContainerVariantId the combo this component was bought as part of, set
     *                exactly when {@code comboSelectionId} is. Display and receipt-header
     *                metadata: the container is never a line and never has an amount
     * @param comboComponentId the {@code catalog.combo_components} pairing the line was
     *                priced from, set exactly when {@code comboSelectionId} is. An order
     *                keeps it so that repricing after an amendment prices the same
     *                component at the same combo price
     * @param comboQuantity how many combos the customer bought on the cart line, the same
     *                on every component line of one selection
     * @param comboPickQuantity how many times the customer picked this component inside
     *                one combo; {@code quantity} is the product of {@code comboQuantity},
     *                the pairing's default quantity and this
     */
    public record Line(
            String lineKey,
            UUID variantId,
            int quantity,
            String descriptionSnapshot,
            long unitAmountMinor,
            long baseAmountMinor,
            long finalAmountMinor,
            long taxAmountMinor,
            @Nullable UUID comboSelectionId,
            @Nullable UUID comboContainerVariantId,
            @Nullable UUID comboComponentId,
            @Nullable Integer comboQuantity,
            @Nullable Integer comboPickQuantity) {

        public Line {
            if ((comboSelectionId == null) != (comboContainerVariantId == null)
                    || (comboSelectionId == null) != (comboComponentId == null)
                    || (comboSelectionId == null) != (comboQuantity == null)
                    || (comboSelectionId == null) != (comboPickQuantity == null)) {
                throw new IllegalArgumentException(
                        "A combo component line carries its selection, container, pairing and quantities, or none");
            }
        }

        /**
         * The cart line this component belongs to: {@code lineKey} without the position
         * suffix a combo's component lines carry, and {@code lineKey} itself on every
         * other line. Ordering reads the cart line's notes and presets through it.
         */
        public String cartLineKey() {
            int tilde = lineKey.lastIndexOf('~');
            return comboSelectionId != null && tilde > 0 ? lineKey.substring(0, tilde) : lineKey;
        }

        /** A line that is not part of a combo, which is every line before ADR 0136. */
        public Line(
                String lineKey,
                UUID variantId,
                int quantity,
                String descriptionSnapshot,
                long unitAmountMinor,
                long baseAmountMinor,
                long finalAmountMinor,
                long taxAmountMinor) {
            this(
                    lineKey,
                    variantId,
                    quantity,
                    descriptionSnapshot,
                    unitAmountMinor,
                    baseAmountMinor,
                    finalAmountMinor,
                    taxAmountMinor,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }

    /**
     * One step of the calculation, in the order it was applied.
     *
     * @param lineKey null for an order-level step such as tax
     * @param sourceId which price book or tax profile produced it, so the figure
     *                 can be re-derived rather than merely believed
     */
    public record Adjustment(
            int sequence,
            @Nullable String lineKey,
            String adjustmentType,
            String sourceType,
            UUID sourceId,
            @Nullable Integer sourceVersion,
            long amountMinor,
            String descriptionCode) {

        /**
         * The {@code sourceType} of an adjustment for a hidden auto-selected modifier option
         * (ADR 0136), whose {@code sourceId} is the option itself. An order reads these to learn
         * which options the server selected for the customer, so the string is the contract
         * between pricing and ordering rather than either module's private spelling.
         */
        public static final String HIDDEN_MODIFIER_SOURCE = "HIDDEN_MODIFIER_OPTION";
    }
}
