package uz.horecaos.platform.ordering.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.OrderReceiptSource;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.ModifierChargeRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderLineRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderModifierRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.pricing.api.QuoteTaxLookup;

/**
 * {@link OrderReceiptSource} over the order, its live lines, the adjustments that itemise their option
 * charges and the tax rule the accepted quote recorded (ADR 0166 Decision 9).
 *
 * <p>Reads and only reads. The lines are the live ones -- an amendment closes a line and appends its
 * replacement, so a receipt built from the closed rows would bill for what the customer no longer has --
 * and every amount is the one the order stored.
 */
@Component
class OrderReceiptReader implements OrderReceiptSource {

    private final JdbcOrderStore orders;
    private final QuoteTaxLookup taxes;

    OrderReceiptReader(JdbcOrderStore orders, QuoteTaxLookup taxes) {
        this.orders = orders;
        this.taxes = taxes;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReceiptOrder> find(UUID tenantId, UUID orderId) {
        return orders.find(tenantId, orderId).map(order -> read(order));
    }

    private ReceiptOrder read(OrderRow order) {
        UUID tenantId = order.tenantId();
        UUID orderId = order.orderId();
        List<OrderLineRow> lines = orders.lines(tenantId, orderId);

        // Names of the options a line carries, by line and option: the order's own snapshot, not the menu.
        Map<UUID, Map<UUID, OrderModifierRow>> modifierRows = new HashMap<>();
        for (OrderModifierRow row : orders.lineModifiers(tenantId, orderId)) {
            modifierRows
                    .computeIfAbsent(row.orderLineId(), line -> new HashMap<>())
                    .putIfAbsent(row.sourceOptionId(), row);
        }

        Map<UUID, List<Option>> optionsByLine = new HashMap<>();
        Map<UUID, Long> unitemisedByLine = new HashMap<>();
        for (ModifierChargeRow charge : orders.modifierCharges(tenantId, orderId)) {
            boolean chosen = QuoteSnapshot.Adjustment.MODIFIER_OPTION_SOURCE.equals(charge.sourceType());
            boolean hidden = QuoteSnapshot.Adjustment.HIDDEN_MODIFIER_SOURCE.equals(charge.sourceType());
            if (!chosen && !hidden) {
                unitemisedByLine.merge(charge.orderLineId(), charge.amountMinor(), Long::sum);
                continue;
            }
            if (charge.sourceId() == null || charge.amountMinor() <= 0) {
                // A hidden option applied at no charge: it is on the order, and it is no receipt line.
                continue;
            }
            OrderModifierRow row =
                    modifierRows.getOrDefault(charge.orderLineId(), Map.of()).get(charge.sourceId());
            optionsByLine
                    .computeIfAbsent(charge.orderLineId(), line -> new ArrayList<>())
                    .add(new Option(
                            charge.sourceId(),
                            row == null ? charge.sourceId().toString() : row.optionName(),
                            charge.amountMinor(),
                            hidden));
        }

        List<Line> receiptLines = new ArrayList<>(lines.size());
        for (OrderLineRow line : lines) {
            Catchweight catchweight =
                    line.catchweightQuantumGrams() == null ? null : new Catchweight(line.actualWeightGrams());
            receiptLines.add(new Line(
                    line.lineId(),
                    line.lineNumber(),
                    line.sourceVariantId(),
                    line.productName(),
                    line.quantity(),
                    line.baseAmountMinor(),
                    line.finalAmountMinor(),
                    line.taxAmountMinor(),
                    catchweight,
                    optionsByLine.getOrDefault(line.lineId(), List.of()),
                    unitemisedByLine.getOrDefault(line.lineId(), 0L)));
        }

        Tax tax = taxes.of(order.tenantId(), order.pricingQuoteId())
                .map(found -> new Tax(found.rateBasisPoints(), found.inclusive()))
                .orElse(null);
        return new ReceiptOrder(
                orderId,
                order.brandId(),
                order.locationId(),
                order.currency(),
                order.totalMinor(),
                order.taxMinor(),
                order.discountMinor(),
                order.feeMinor(),
                order.pricingQuoteId(),
                tax,
                receiptLines);
    }
}
