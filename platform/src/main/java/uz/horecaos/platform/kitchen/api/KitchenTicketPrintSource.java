package uz.horecaos.platform.kitchen.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The ticket as a printer needs it (ADR 0154): the kitchen's own read, through its own port, so the
 * printing module never reads {@code kitchen.*} tables.
 *
 * <p>Ids, quantities and the station names the kitchen already keeps in three languages (ADR 0041: a
 * ticket renders in the kitchen's locale). Dish names, modifiers and notes are not here -- the kitchen
 * carries none (a display resolves them through the order) -- so a document joins {@link Item#orderLineId}
 * to the order's lines. No customer identity of any kind.
 *
 * <p>Tenant-scoped in every query: a ticket id from another tenant answers as if it did not exist.
 */
public interface KitchenTicketPrintSource {

    Optional<PrintableTicket> find(UUID tenantId, UUID ticketId);

    /** The ticket an order opened, if the branch runs a kitchen screen and one was opened. */
    Optional<PrintableTicket> findByOrder(UUID tenantId, UUID orderId);

    /**
     * Tickets of one location released in {@code [from, to)}, oldest first: what the reconciling sweeper
     * re-derives jobs from after a lost listener.
     */
    List<ReleasedTicket> releasedBetween(UUID tenantId, UUID locationId, Instant from, Instant to, int limit);

    record ReleasedTicket(UUID ticketId, UUID orderId, int amendmentRevision, Instant releasedAt) {}

    /**
     * @param releasedAt         null for a ticket still on hold
     * @param targetReadyAt      when the food must be on the pass, null when the order carried no promise
     * @param amendmentRevision  how many amendments changed the ticket after it was created
     * @param items              the ticket's items that are still to be made or made: a struck item
     *                           (cancelled by an amendment) is not here
     */
    record PrintableTicket(
            UUID ticketId,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID orderId,
            String sequenceLabel,
            String fulfilmentMode,
            String channelCode,
            String status,
            @Nullable Instant releasedAt,
            @Nullable Instant targetReadyAt,
            int amendmentRevision,
            List<Station> stations,
            List<Item> items) {

        public PrintableTicket {
            stations = List.copyOf(stations);
            items = List.copyOf(items);
        }
    }

    record Station(UUID id, String code, String nameRu, String nameUz, String nameEn, boolean fallback) {

        public String name(String locale) {
            return switch (locale) {
                case "ru" -> nameRu;
                case "uz-Latn" -> nameUz;
                default -> nameEn;
            };
        }
    }

    /**
     * @param quantity a decimal since ADR 0137
     * @param comboSelectionId ADR 0136: the key the component items of one combo share
     */
    record Item(
            UUID itemId,
            UUID orderLineId,
            UUID stationId,
            BigDecimal quantity,
            @Nullable UUID comboSelectionId) {}
}
