package uz.horecaos.platform.printing.application.documents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.kitchen.api.KitchenTicketPrintSource.PrintableTicket;
import uz.horecaos.platform.kitchen.api.KitchenTicketPrintSource.Station;
import uz.horecaos.platform.ordering.api.OrderPrintSource.Line;
import uz.horecaos.platform.ordering.api.OrderPrintSource.PrintableOrder;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrintDocument.Block;
import uz.horecaos.platform.printing.domain.PrintDocument.Item;
import uz.horecaos.platform.printing.domain.PrintDocument.KeyValue;
import uz.horecaos.platform.printing.domain.PrintDocument.Size;
import uz.horecaos.platform.printing.domain.PrintDocument.Text;
import uz.horecaos.platform.printing.domain.PrintDocument.Value;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.render.PrintLabels;

/**
 * One station's items of one ticket (ADR 0154, Decision 2): the sequence label large, the fulfilment mode,
 * the channel code, the table where there is a session, the station, when it was released and when it is
 * promised, and a quantity and name per line with its modifiers, its coded presets and its note.
 *
 * <p>A station's ticket carries none of another station's lines. A combo prints as one header line with
 * only the components routed to this station beneath it (ADR 0136). There is no customer name, phone or
 * address on it, because none is read: the order port does not carry one.
 */
public final class KitchenTicketDocument {

    private KitchenTicketDocument() {}

    /**
     * @param amendmentRevision 0 for the ticket as released; N for the Nth amendment since, which is marked
     * @param tableName         the table an ADR 0047 session seats this order at, if any
     */
    public static PrintDocument build(
            BuildContext context,
            PrintableTicket ticket,
            PrintableOrder order,
            UUID stationId,
            @Nullable String tableName,
            int amendmentRevision) {
        PrintLabels labels = context.labels();
        String station = ticket.stations().stream()
                .filter(candidate -> candidate.id().equals(stationId))
                .map(candidate -> candidate.name(context.locale()))
                .findFirst()
                .orElse("?");

        Map<UUID, Line> lines = new LinkedHashMap<>();
        order.lines().forEach(line -> lines.put(line.lineId(), line));

        List<Block> blocks = new ArrayList<>();
        blocks.add(Text.title(ticket.sequenceLabel(), Size.TRIPLE));
        blocks.add(Text.title(labels.get("title.kitchen_ticket") + " · " + station, Size.NORMAL));
        if (amendmentRevision > 0) {
            blocks.add(Text.title(labels.get("ticket.amended") + " №" + amendmentRevision, Size.NORMAL));
        }
        blocks.add(new PrintDocument.StatusSlot());
        blocks.add(KeyValue.of(labels.get("order.mode"), modeLabel(labels, ticket.fulfilmentMode())));
        blocks.add(KeyValue.of(labels.get("order.channel"), ticket.channelCode()));
        if (tableName != null && !tableName.isBlank()) {
            blocks.add(KeyValue.of(labels.get("order.table"), tableName));
        }
        if (ticket.releasedAt() != null) {
            blocks.add(new KeyValue(
                    labels.get("ticket.released"),
                    Value.Moment.of(ticket.releasedAt(), Value.Moment.Format.TIME),
                    false));
        }
        if (ticket.targetReadyAt() != null) {
            blocks.add(new KeyValue(
                    labels.get("ticket.ready_by"),
                    Value.Moment.of(ticket.targetReadyAt(), Value.Moment.Format.TIME),
                    true));
        }
        blocks.add(new PrintDocument.Rule(true));

        // Items in the order the kitchen routed them, combos gathered under one header at the first of
        // their components.
        Map<UUID, List<Item>> comboComponents = new LinkedHashMap<>();
        Map<UUID, Line> comboHeads = new LinkedHashMap<>();
        List<Object> ordered = new ArrayList<>();
        for (var item : ticket.items()) {
            if (!item.stationId().equals(stationId)) {
                continue;
            }
            Line line = lines.get(item.orderLineId());
            if (line == null) {
                // The line was closed by an amendment between the ticket read and the order read: the
                // next amendment ticket carries its replacement.
                continue;
            }
            Item printed = new Item(
                    LineLabels.name(line),
                    LineLabels.quantity(item.quantity()),
                    null,
                    false,
                    LineLabels.details(line, context.locale(), true));
            UUID selection = item.comboSelectionId();
            if (selection == null) {
                ordered.add(printed);
            } else {
                if (!comboComponents.containsKey(selection)) {
                    ordered.add(selection);
                    comboHeads.put(selection, line);
                }
                comboComponents
                        .computeIfAbsent(selection, key -> new ArrayList<>())
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
        if (order.kitchenNote() != null && !order.kitchenNote().isBlank()) {
            blocks.add(new PrintDocument.Rule(false));
            blocks.add(Text.bold("! " + order.kitchenNote().strip()));
        }
        blocks.add(new PrintDocument.Rule(true));
        blocks.add(new PrintDocument.Feed(1));
        blocks.add(new PrintDocument.Cut());
        return PrintDocument.of(
                PrintDocumentType.KITCHEN_TICKET,
                context.locale(),
                context.tape().millimetres(),
                context.zone(),
                context.copy(),
                blocks);
    }

    static String modeLabel(PrintLabels labels, String mode) {
        String key = "order.mode." + mode;
        return labels.has(key) ? labels.get(key) : mode;
    }

    /** Whether any item of {@code ticket} is routed to {@code station}: a station with none prints nothing. */
    public static boolean hasItemsFor(PrintableTicket ticket, UUID station) {
        return ticket.items().stream().anyMatch(item -> item.stationId().equals(station));
    }

    /** The stations that have at least one item on this ticket, in the order the kitchen lists its stations. */
    public static List<Station> stationsWithItems(PrintableTicket ticket) {
        return ticket.stations().stream()
                .filter(station -> hasItemsFor(ticket, station.id()))
                .toList();
    }
}
