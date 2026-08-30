package uz.horecaos.platform.pricing.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
 */
public record QuoteSnapshot(
        UUID quoteId,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID customerAccountId,
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
        List<Adjustment> adjustments) {

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

    /** @param lineKey the cart's stable line key, so lines match up without relying on order */
    public record Line(
            String lineKey,
            UUID variantId,
            int quantity,
            String descriptionSnapshot,
            long unitAmountMinor,
            long baseAmountMinor,
            long finalAmountMinor,
            long taxAmountMinor) {}

    /**
     * One step of the calculation, in the order it was applied.
     *
     * @param lineKey null for an order-level step such as tax
     * @param sourceId which price book or tax profile produced it, so the figure
     *                 can be re-derived rather than merely believed
     */
    public record Adjustment(
            int sequence,
            String lineKey,
            String adjustmentType,
            String sourceType,
            UUID sourceId,
            Integer sourceVersion,
            long amountMinor,
            String descriptionCode) {}
}
