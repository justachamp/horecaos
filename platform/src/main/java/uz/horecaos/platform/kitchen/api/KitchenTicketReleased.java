package uz.horecaos.platform.kitchen.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A production ticket left the buffer and reached its stations (ADR 0041, ADR 0154): {@code HELD} to
 * {@code FIRED}, whether the order was confirmed into an immediate release, a scheduled one came due, or a
 * person pressed "release now".
 *
 * <p>The signal a branch's printing listens for to put the ticket on paper. For a preorder that is its own
 * release instant and never the confirmation: the ADR 0041 rule that stopped a 20:00 order printing at
 * 11:00.
 *
 * <p>An in-process signal only, exactly like {@code FiscalDocumentIssued}: identifiers, no ADR 0032
 * catalogue entry, never appended to the outbox. Published inside the releasing transaction; a listener
 * that must not be able to roll the release back runs {@code AFTER_COMMIT}.
 */
public record KitchenTicketReleased(
        UUID eventId, UUID tenantId, UUID brandId, UUID locationId, UUID ticketId, UUID orderId, Instant releasedAt) {

    public KitchenTicketReleased {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(ticketId, "Ticket ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(releasedAt, "Release time is required");
    }
}
