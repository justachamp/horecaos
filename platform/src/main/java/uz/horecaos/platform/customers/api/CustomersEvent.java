package uz.horecaos.platform.customers.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A versioned business fact emitted by the customers module (ADR 0111, ADR 0032).
 *
 * <p>Sealed, in the {@code InventoryEvent} / {@code PricingEvent} genre, so a future subtype cannot
 * reach {@link uz.horecaos.platform.integration.outbox.CustomersOutboxEventListener} without a
 * catalogue entry, a schema and a documentation row.
 *
 * <p>ADR 0111 names four lead facts. They exist so a future marketing trigger or a reporting fact
 * can subscribe through ADR 0005's inbox without {@code customers} knowing who is listening, and
 * every payload is identifiers and stable codes -- never a phone number, a name or a note (ADR
 * 0029). The aggregate is the lead, so one lead's facts stay in order on their topic.
 */
public sealed interface CustomersEvent
        permits LeadRegistered, LeadStatusChanged, LeadAssignedToLocation, LeadConverted {

    UUID eventId();

    String eventType();

    int eventVersion();

    UUID tenantId();

    UUID leadId();

    Instant occurredAt();

    Object payload();

    default String aggregateType() {
        return "Lead";
    }

    default UUID aggregateId() {
        return leadId();
    }
}
