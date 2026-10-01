package uz.horecaos.platform.pricing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeOutcome;
import uz.horecaos.platform.web.api.Quantities;

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
     * @param lineKey the cart's stable line key, so lines match up without relying on order
     * @param quantity a decimal since ADR 0137, normalised: {@code 2}, never {@code 2.000}
     * @param catchweight ADR 0137: set only for a catchweight variant, and what makes the
     *                    line's amounts provisional until a weight is captured
     */
    public record Line(
            String lineKey,
            UUID variantId,
            BigDecimal quantity,
            String descriptionSnapshot,
            long unitAmountMinor,
            long baseAmountMinor,
            long finalAmountMinor,
            long taxAmountMinor,
            @Nullable Catchweight catchweight) {

        public Line {
            quantity = Quantities.normalise(quantity);
        }

        /** A line that is not catchweight. */
        public Line(
                String lineKey,
                UUID variantId,
                BigDecimal quantity,
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
                    null);
        }
    }

    /**
     * What makes a quote line provisional (ADR 0137): the price is per {@code
     * quantumGrams}, and the amounts were computed against {@code nominalGramsPerUnit}
     * until {@code actualWeightGrams} says otherwise.
     *
     * @param pricePerQuantumMinor the price row's amount: minor units per quantum, not per unit
     * @param actualWeightGrams    null while provisional; the weighed total of the whole line after
     */
    public record Catchweight(
            int quantumGrams,
            int nominalGramsPerUnit,
            long pricePerQuantumMinor,
            @Nullable Integer actualWeightGrams) {

        public boolean reconciled() {
            return actualWeightGrams != null;
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
            String descriptionCode) {}
}
