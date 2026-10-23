package uz.horecaos.platform.ordering.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An order as a fiscal receipt needs it (ADR 0166 Decision 9, ADR 0038, ADR 0018).
 *
 * <p>Every order, not only a marked one: priced lines, each priced modifier option as its own entry,
 * the delivery fee, the discounts the order carries, and the tax rule the accepted quote extracted VAT
 * under. Consumer-declared and implemented by {@code ordering}, which owns the order and the quote it
 * accepted; {@code payments} reads it and builds the receipt lines, and nothing here is computed: every
 * figure is the order's own, copied from the quote at checkout (or re-copied by an amendment).
 *
 * <p>{@link OrderMarksPort#receiptSource} answers the narrower question of an order's marking codes and
 * stays the only way to reach one; this carries none.
 *
 * <p>Tenant-scoped in every read: an order id from another tenant answers as though it did not exist. No
 * customer identity crosses this port, and no free-text note.
 */
public interface OrderReceiptSource {

    /** @return empty when no order of that id belongs to this tenant */
    Optional<ReceiptOrder> find(UUID tenantId, UUID orderId);

    /**
     * @param discountMinor everything the order was discounted by: the item discounts already taken off
     *     the lines' {@code finalMinor} and the order-level discount that was not allocated to any line
     * @param feeMinor the delivery fee as charged, after any waiver or benefit; zero when there is none
     * @param tax the rule the accepted quote extracted VAT under, or null when the quote recorded none
     * @param lines every live line, in line order
     */
    record ReceiptOrder(
            UUID orderId,
            UUID brandId,
            UUID locationId,
            String currency,
            long totalMinor,
            long taxMinor,
            long discountMinor,
            long feeMinor,
            UUID quoteId,
            @Nullable Tax tax,
            List<Line> lines) {

        public ReceiptOrder {
            lines = List.copyOf(lines);
        }
    }

    /**
     * @param rateBasisPoints ADR 0018's rate, 1200 for 12 percent
     * @param inclusive whether the prices were VAT-inclusive
     */
    record Tax(int rateBasisPoints, boolean inclusive) {}

    /**
     * One live order line.
     *
     * @param baseMinor the line's gross before any discount, modifiers included
     * @param finalMinor the line's gross after the item discounts that were taken off it
     * @param taxMinor the quote's recorded tax share of the line, never recomputed
     * @param catchweight set only for a line sold by weight
     * @param options the priced options the line's gross contains, each with what it charged; the
     *     variant's own part of the line is {@code baseMinor} less these
     * @param unitemisedModifiersMinor what the line charged for modifier options that the order did not
     *     itemise (an order from before options were), which no receipt can split; zero otherwise
     */
    record Line(
            UUID lineId,
            int lineNumber,
            UUID variantId,
            String productName,
            BigDecimal quantity,
            long baseMinor,
            long finalMinor,
            long taxMinor,
            @Nullable Catchweight catchweight,
            List<Option> options,
            long unitemisedModifiersMinor) {

        public Line {
            options = List.copyOf(options);
        }
    }

    /**
     * What makes a line sold by weight provisional until a weight is captured (ADR 0137).
     *
     * @param actualWeightGrams the weighed total of the whole line, or null while it is provisional
     */
    record Catchweight(@Nullable Integer actualWeightGrams) {

        public boolean reconciled() {
            return actualWeightGrams != null;
        }
    }

    /**
     * A priced modifier option on a line.
     *
     * @param amountMinor what the option charged on the line, quantity included
     * @param hidden whether the server applied it and the customer never chose it
     */
    record Option(UUID optionId, String name, long amountMinor, boolean hidden) {}
}
