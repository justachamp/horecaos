package uz.horecaos.platform.printing.application.documents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.ordering.api.OrderPrintSource.Line;
import uz.horecaos.platform.ordering.api.OrderPrintSource.PrintableOrder;
import uz.horecaos.platform.payments.api.PaymentDirectory.PaymentSummary;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrintDocument.Block;
import uz.horecaos.platform.printing.domain.PrintDocument.Item;
import uz.horecaos.platform.printing.domain.PrintDocument.KeyValue;
import uz.horecaos.platform.printing.domain.PrintDocument.Size;
import uz.horecaos.platform.printing.domain.PrintDocument.Text;
import uz.horecaos.platform.printing.domain.PrintDocument.Value;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.render.PrintLabels;
import uz.horecaos.platform.tenancy.api.FiscalSeller;

/**
 * An order's summary (ADR 0154, Decision 2): the branch, its seller, the order number, the lines and the
 * totals, the tender and, for cash, what was tendered and the change due, and one line of fiscal status.
 *
 * <p><strong>Never a fiscal document, and never headed as a receipt.</strong> The header says it is not a
 * fiscal document, and the one line that states the fiscal status is the only place the word «чек» /
 * «receipt» appears on it (ADR 0154, Decision 5): a slip a customer could take for a receipt is the harm
 * this record is built to avoid. Every figure is the order's own, as stored; the slip adds nothing up.
 *
 * <p>No customer identity -- none is read -- and no line note or preset either: the slip is what the
 * cashier and the customer see, and a note is the customer's own typing. No logo and no footer, for the
 * same reason: what a tenant authors for its receipt must not be able to turn a slip into one.
 */
public final class OrderSlipDocument {

    private OrderSlipDocument() {}

    /**
     * @param seller    who sold at this branch on the order's business date, if a legal entity is assigned
     * @param payment   the payment facts payments states for the order, if any
     * @param tableName the table an ADR 0047 session seats this order at, if any
     */
    public static PrintDocument build(
            BuildContext context,
            PrintableOrder order,
            @Nullable FiscalSeller seller,
            @Nullable PaymentSummary payment,
            @Nullable String tableName) {
        PrintLabels labels = context.labels();
        String currency = order.currency();
        List<Block> blocks = new ArrayList<>();

        blocks.add(Text.title(context.branch().name(), Size.NORMAL));
        if (context.branch().address() != null) {
            blocks.add(Text.centered(context.branch().address()));
        }
        if (context.branch().phone() != null) {
            blocks.add(Text.centered(context.branch().phone()));
        }
        blocks.add(Text.title(labels.get("title.order_slip"), Size.DOUBLE));
        blocks.add(Text.title(labels.get("slip.not_fiscal"), Size.NORMAL));
        blocks.add(new PrintDocument.StatusSlot());
        blocks.add(KeyValue.of(labels.get("order.number"), order.publicOrderNumber()));
        blocks.add(new KeyValue(
                labels.get("order.placed"),
                Value.Moment.of(
                        order.confirmedAt() != null ? order.confirmedAt() : order.placedAt(),
                        Value.Moment.Format.DATE_TIME),
                false));
        blocks.add(KeyValue.of(
                labels.get("order.mode"), KitchenTicketDocument.modeLabel(labels, order.fulfillmentMode())));
        blocks.add(KeyValue.of(labels.get("order.channel"), order.channelCode()));
        if (tableName != null && !tableName.isBlank()) {
            blocks.add(KeyValue.of(labels.get("order.table"), tableName));
        }
        if (seller != null) {
            blocks.add(KeyValue.of(labels.get("seller"), seller.legalName()));
            blocks.add(KeyValue.of(labels.get("tin"), seller.taxpayerNumber()));
        }
        blocks.add(new PrintDocument.Rule(false));

        Map<UUID, List<Item>> comboComponents = new LinkedHashMap<>();
        Map<UUID, Line> comboHeads = new LinkedHashMap<>();
        List<Object> ordered = new ArrayList<>();
        for (Line line : order.lines()) {
            Item printed = new Item(
                    LineLabels.name(line),
                    LineLabels.quantity(line.quantity()),
                    Value.Amount.row(line.finalAmountMinor(), currency),
                    false,
                    LineLabels.details(line, context.locale(), false));
            if (line.comboSelectionId() == null) {
                ordered.add(printed);
            } else {
                if (!comboComponents.containsKey(line.comboSelectionId())) {
                    ordered.add(line.comboSelectionId());
                    comboHeads.put(line.comboSelectionId(), line);
                }
                comboComponents
                        .computeIfAbsent(line.comboSelectionId(), key -> new ArrayList<>())
                        .add(printed);
            }
        }
        for (Object entry : ordered) {
            if (entry instanceof Item plain) {
                blocks.add(plain);
            } else {
                UUID selection = (UUID) entry;
                Line head = Objects.requireNonNull(comboHeads.get(selection));
                String name = head.comboName() == null ? LineLabels.name(head) : head.comboName();
                String quantity = head.comboQuantity() == null ? null : Integer.toString(head.comboQuantity());
                blocks.add(
                        new Item(name, quantity, null, true, Objects.requireNonNull(comboComponents.get(selection))));
            }
        }
        blocks.add(new PrintDocument.Rule(false));

        blocks.add(KeyValue.amount(labels.get("totals.subtotal"), order.subtotalMinor(), currency, false));
        if (order.discountMinor() > 0) {
            blocks.add(KeyValue.amount(labels.get("totals.discount"), -order.discountMinor(), currency, false));
        }
        if (order.feeMinor() > 0) {
            blocks.add(KeyValue.amount(labels.get("totals.fee"), order.feeMinor(), currency, false));
        }
        if (order.taxMinor() > 0) {
            blocks.add(KeyValue.amount(labels.get("totals.tax"), order.taxMinor(), currency, false));
        }
        blocks.add(KeyValue.amount(labels.get("totals.total"), order.totalMinor(), currency, true));

        if (payment != null) {
            String tenderKey = "pay.tender." + payment.tender();
            blocks.add(KeyValue.of(
                    labels.get("pay.method"), labels.has(tenderKey) ? labels.get(tenderKey) : payment.tender()));
        }
        if (order.cashTenderedMinor() != null) {
            blocks.add(KeyValue.amount(labels.get("pay.cash_tendered"), order.cashTenderedMinor(), currency, false));
            if (order.changeDueMinor() != null) {
                blocks.add(KeyValue.amount(labels.get("pay.change_due"), order.changeDueMinor(), currency, true));
            }
        }
        blocks.add(new PrintDocument.Rule(false));
        blocks.add(Text.centered(fiscalStatusLine(labels, payment)));
        // No footer: it is text a tenant writes, and a slip carries nothing but what the record lists, so
        // that the fiscal-status line stays the only place «чек» / «receipt» can appear on it.
        blocks.add(new PrintDocument.Feed(1));
        blocks.add(new PrintDocument.Cut());
        return PrintDocument.of(
                PrintDocumentType.ORDER_SLIP,
                context.locale(),
                context.tape().millimetres(),
                context.zone(),
                context.copy(),
                blocks);
    }

    /**
     * The one line that states the fiscal status, and the only place the word «чек» / «receipt» appears on a
     * slip. The status is payments' own word for the order's latest fiscal document; an order with no
     * document at all says there is no record.
     */
    static String fiscalStatusLine(PrintLabels labels, @Nullable PaymentSummary payment) {
        String status = payment == null || payment.fiscalStatus() == null ? "NONE" : payment.fiscalStatus();
        String key = "slip.fiscal_status." + status;
        return labels.has(key) ? labels.get(key) : labels.get("slip.fiscal_status.NONE");
    }
}
