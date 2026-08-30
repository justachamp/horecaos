package uz.horecaos.platform.pricing.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A priced cart (ADR 0018).
 *
 * <p>Every amount that made up the total is recorded, because a customer asking
 * "why is this 47,000 som" and an auditor asking the same question need the same
 * answer, and neither can be given one by a total alone.
 */
public record Quote(
        UUID quoteId,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID customerAccountId,
        String currency,
        Status status,
        UUID catalogPublicationId,
        int calculationVersion,
        String contextHash,
        Money subtotal,
        Money tax,
        Money fees,
        Money discount,
        Money total,
        List<QuoteLine> lines,
        List<Adjustment> adjustments,
        Instant expiresAt,
        Instant createdAt) {

    public enum Status {
        ACTIVE,
        ACCEPTED,
        /** Passed its TTL. Re-quoting is the only way forward; the price is not honoured. */
        EXPIRED,
        /** Replaced by a newer quote for the same cart. */
        SUPERSEDED
    }

    public boolean isAcceptableAt(Instant now) {
        return status == Status.ACTIVE && expiresAt.isAfter(now);
    }

    /**
     * @param type                what kind of line this is. ADR 0037 makes the
     *                            delivery charge a line rather than an adjustment,
     *                            because this market requires it on its own receipt
     *                            line with its own classification and tax share,
     *                            and an adjustment has neither
     * @param variantId           null on a {@link LineType#DELIVERY_FEE} line and
     *                            never on an item line
     * @param descriptionSnapshot the name as shown at pricing time. Copied, so a
     *                            menu rename cannot change what a historical quote
     *                            says the customer was buying
     */
    public record QuoteLine(
            String lineId,
            LineType type,
            UUID variantId,
            int quantity,
            String descriptionSnapshot,
            Money unitAmount,
            Money baseAmount,
            Money finalAmount,
            Money taxAmount) {

        public QuoteLine {
            // Mirrors ck_quote_line_variant_agrees, stated as an equivalence so
            // neither kind of line can be built as the other. A fee line carrying a
            // variant would be snapshotted onto an order as a basket item.
            if ((type == LineType.ITEM) != (variantId != null)) {
                throw new IllegalArgumentException(
                        "An item line needs a variant and a fee line must not have one: " + type);
            }
        }

        /** An ordinary basket line. */
        public static QuoteLine item(
                String lineId,
                UUID variantId,
                int quantity,
                String descriptionSnapshot,
                Money unitAmount,
                Money baseAmount,
                Money finalAmount,
                Money taxAmount) {
            return new QuoteLine(
                    lineId,
                    LineType.ITEM,
                    variantId,
                    quantity,
                    descriptionSnapshot,
                    unitAmount,
                    baseAmount,
                    finalAmount,
                    taxAmount);
        }
    }

    /** ADR 0037. What a quote line represents. */
    public enum LineType {
        ITEM,
        /**
         * The delivery charge. Quantity is always one: a fee is not something a
         * customer buys two of, and expressing it as a quantity would make the
         * receipt line arithmetic look like a unit price it is not.
         */
        DELIVERY_FEE
    }

    /**
     * One step of the calculation, in the order it was applied.
     *
     * @param sourceType and sourceId identify what caused it — a price book, a
     *                   tax profile — so the same inputs can be re-derived later
     */
    public record Adjustment(
            int sequence,
            String lineId,
            Type type,
            String sourceType,
            UUID sourceId,
            Integer sourceVersion,
            Money amount,
            String descriptionCode) {

        public enum Type {
            BASE_PRICE,
            MODIFIER,
            ITEM_DISCOUNT,
            ORDER_DISCOUNT,
            FEE,
            TAX,
            ROUNDING,
            /**
             * ADR 0037 stage 8. The zone's own free-delivery threshold, recorded as
             * a negative adjustment rather than by computing the fee as zero,
             * because a zero with no adjustment beside it cannot be told apart from
             * a broken tariff lookup.
             */
            DELIVERY_FEE_WAIVER,
            /**
             * ADR 0037 stage 9. A promotion's free-delivery grant, applied after
             * the waiver and capped at whatever fee the waiver left. Nothing writes
             * one yet — ADR 0018's promotion stages are unbuilt — and the value
             * exists here and in the schema so that when they are, the grant lands
             * as its own fact rather than as a second waiver.
             */
            DELIVERY_FEE_BENEFIT,
            /**
             * ADR 0037 stage 6, added in V0032. The rate table's own standing
             * discount, resolved in fulfillment with the fee because it is a
             * function of distance and the local clock, neither of which this
             * engine may read.
             *
             * <p>Not the stage 8 waiver under another name: that one asks whether
             * the basket cleared a threshold, and this one asks what time it is and
             * how far away the customer lives. Naming them the same would make the
             * two indistinguishable in every report that groups by adjustment type,
             * and they answer to different owners — the waiver to a zone, this to a
             * rate table.
             */
            DELIVERY_TARIFF_DISCOUNT
        }
    }
}
