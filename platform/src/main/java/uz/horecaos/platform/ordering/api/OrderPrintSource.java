package uz.horecaos.platform.ordering.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The order as a paper document needs it (ADR 0154), read through ordering's own port.
 *
 * <p>The printing module renders and delivers and decides nothing commercial: every figure on a kitchen
 * ticket or an order slip is read here and printed as stored. Nothing is computed -- the totals are the
 * order's columns, a line's amount is the line's, and the change due is the same {@code tendered - total}
 * the order detail shows.
 *
 * <p><strong>No customer identity crosses this port.</strong> There is no name, phone, address or
 * delivery instruction on any record below: the paper never carries one (ADR 0154, Decision 12). What
 * does cross is a line's free-text note, decrypted for the print (ADR 0029): a customer may have typed
 * anything into it, which is why the document it lands in is stored envelope-encrypted and erased on a
 * schedule. The reveal names its purpose.
 *
 * <p>Tenant-scoped in every query: an order id from another tenant answers as if it did not exist.
 */
public interface OrderPrintSource {

    /**
     * The order's current lines (an amendment closes a line and appends its replacement, so only open
     * lines are returned), header and money.
     *
     * @param revealPurpose recorded against the decryption of a line note (ADR 0029); {@code null} reveals
     *                      no note at all, which is what a document that does not print one asks for
     * @return empty when no order of that id belongs to this tenant
     */
    Optional<PrintableOrder> find(UUID tenantId, UUID orderId, @Nullable String revealPurpose);

    /**
     * Orders of one location whose confirmation fell in {@code [from, to)}, oldest first: what the
     * reconciling sweeper re-derives jobs from after a lost listener.
     */
    List<ConfirmedOrder> confirmedBetween(UUID tenantId, UUID locationId, Instant from, Instant to, int limit);

    /** The confirmation a sweeper keys an order slip on: the order and the version it was confirmed at. */
    record ConfirmedOrder(UUID orderId, int version, Instant confirmedAt) {}

    /**
     * @param publicOrderNumber the short number a receipt and a kitchen ticket both print
     * @param placedAt          when the order was received
     * @param cashTenderedMinor what the cashier was told to expect from the customer, null when the order
     *                          is not paid in cash or none was recorded
     * @param changeDueMinor    {@code tendered - total}, as the order detail shows it; null when there is
     *                          no tendered amount
     * @param kitchenNote       the order-level note for the kitchen, a column the order keeps in clear
     */
    record PrintableOrder(
            UUID orderId,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String publicOrderNumber,
            String status,
            String fulfillmentMode,
            String channelCode,
            Instant placedAt,
            @Nullable Instant confirmedAt,
            int version,
            String currency,
            long subtotalMinor,
            long taxMinor,
            long discountMinor,
            long feeMinor,
            long totalMinor,
            @Nullable Long cashTenderedMinor,
            @Nullable Long changeDueMinor,
            @Nullable String kitchenNote,
            List<Line> lines) {

        public PrintableOrder {
            lines = List.copyOf(lines);
        }
    }

    /**
     * @param quantity           a decimal since ADR 0137
     * @param note               the line's free-text note, decrypted for the print; null when none
     * @param comboSelectionId   ADR 0136: the key the component lines of one combo purchase share
     * @param comboName          the combo as it was sold (a snapshot), set exactly when {@code comboSelectionId} is
     * @param comboQuantity      combos bought, the same on every component of one selection
     */
    record Line(
            UUID lineId,
            int lineNumber,
            String productName,
            @Nullable String variantName,
            BigDecimal quantity,
            long unitAmountMinor,
            long finalAmountMinor,
            @Nullable String note,
            List<Modifier> modifiers,
            List<Preset> presets,
            @Nullable UUID comboSelectionId,
            @Nullable String comboName,
            @Nullable Integer comboQuantity) {

        public Line {
            modifiers = List.copyOf(modifiers);
            presets = List.copyOf(presets);
        }
    }

    /**
     * @param parentId the first-level selection whose linked variant offered this one (ADR 0136: one level
     *                 of nesting and no more), null on a first-level selection
     */
    record Modifier(
            UUID id,
            @Nullable UUID parentId,
            @Nullable String groupName,
            String optionName,
            int quantity,
            long finalAmountMinor) {}

    /** A coded kitchen instruction chosen at checkout, its label copied in the three languages (V0397, V0433). */
    record Preset(String code, String labelRu, String labelUz, String labelEn) {

        public String label(String locale) {
            return switch (locale) {
                case "ru" -> labelRu;
                case "uz-Latn" -> labelUz;
                default -> labelEn;
            };
        }
    }
}
