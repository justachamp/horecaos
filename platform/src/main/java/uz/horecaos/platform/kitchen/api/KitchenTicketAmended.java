package uz.horecaos.platform.kitchen.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An order amendment changed the items of a ticket that had already been released (ADR 0039, ADR 0041,
 * ADR 0136, ADR 0154): a drink added at the table, a line struck, a combo made larger.
 *
 * <p>Carries the kitchen's own amendment fact: {@code revision} is the ticket's amendment counter after
 * this change, so a replayed signal names the same revision and prints once, and the next amendment prints
 * again. Not published for a ticket still on hold -- it has not been cooked, and its release prints the
 * order as it then is.
 *
 * <p>An in-process signal only, like {@link KitchenTicketReleased}.
 */
public record KitchenTicketAmended(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID ticketId,
        UUID orderId,
        int revision,
        Instant occurredAt) {

    public KitchenTicketAmended {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(ticketId, "Ticket ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        if (revision < 1) {
            throw new IllegalArgumentException("An amendment is revision 1 or later");
        }
    }
}
