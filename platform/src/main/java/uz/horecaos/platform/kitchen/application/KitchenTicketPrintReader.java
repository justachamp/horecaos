package uz.horecaos.platform.kitchen.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.kitchen.api.KitchenTicketPrintSource;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.StationRow;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketItemRow;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketRow;

/**
 * {@link KitchenTicketPrintSource} over the kitchen store (ADR 0154).
 *
 * <p>The kitchen answers from its own tables and nobody else reads them. Reads only, and nothing here
 * carries a dish name, a note or a person: the kitchen keeps none, and the document that is built from
 * this joins the item's order line to the order for the words.
 */
@Component
class KitchenTicketPrintReader implements KitchenTicketPrintSource {

    private final JdbcKitchenStore kitchen;

    KitchenTicketPrintReader(JdbcKitchenStore kitchen) {
        this.kitchen = kitchen;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PrintableTicket> find(UUID tenantId, UUID ticketId) {
        return kitchen.findTicket(tenantId, ticketId).map(this::printable);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PrintableTicket> findByOrder(UUID tenantId, UUID orderId) {
        return kitchen.findTicketByOrder(tenantId, orderId).map(this::printable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReleasedTicket> releasedBetween(UUID tenantId, UUID locationId, Instant from, Instant to, int limit) {
        return kitchen.releasedBetween(tenantId, locationId, from, to, limit).stream()
                .map(row ->
                        new ReleasedTicket(row.ticketId(), row.orderId(), row.amendmentRevision(), row.releasedAt()))
                .toList();
    }

    private PrintableTicket printable(TicketRow ticket) {
        List<TicketItemRow> items = kitchen.liveItemsOf(ticket.tenantId(), ticket.id());
        List<StationRow> stations = kitchen.listStations(ticket.tenantId(), ticket.locationId());
        return new PrintableTicket(
                ticket.id(),
                ticket.tenantId(),
                ticket.brandId(),
                ticket.locationId(),
                ticket.orderId(),
                ticket.sequenceLabel(),
                ticket.fulfilmentMode(),
                ticket.channelCode(),
                ticket.status().name(),
                ticket.releasedAt(),
                ticket.targetReadyAt(),
                kitchen.amendmentRevisionOf(ticket.tenantId(), ticket.id()),
                stations.stream()
                        .map(station -> new Station(
                                station.id(),
                                station.code(),
                                station.displayNameRu(),
                                station.displayNameUz(),
                                station.displayNameEn(),
                                station.fallback()))
                        .toList(),
                items.stream()
                        .map(item -> new Item(
                                item.id(),
                                item.orderLineId(),
                                item.stationId(),
                                item.quantity(),
                                item.comboSelectionId()))
                        .toList());
    }
}
